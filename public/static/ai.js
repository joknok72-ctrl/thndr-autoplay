/* ===== THNDR AI — strongest-move planner =====
   Strategy:
   1. Try all 6 orderings of the 3 pieces.
   2. Beam search over placements (beam width depends on level).
   3. Evaluate = immediate points (with streak / multiplier / bonus cells)
                 + board quality (holes, fragmentation, open space, big-piece fit, near-full lines)
                 + multiplier growth value + survival (probe pieces still placeable).
   Works in the main thread or inside a Web Worker (see ai-worker.js).
*/
(function (global) {
  'use strict';
  const E = global.Engine; const N = E.N; const idx = E.idx;

  // probe pieces used to measure "future placeability"
  const PROBES = ['sq3','i5','v5','sq2','Lbig1','T4a','i4','v4','l3a','i3','v3','plus'].map(k => E.makePiece(k));
  const PROBE_W = { sq3: 3.5, i5: 2, v5: 2, sq2: 1.5, Lbig1: 1.5, T4a: 1, i4: 1, v4: 1, l3a: .6, i3: .6, v3: .6, plus: 1 };

  const LEVELS = { 1: { beam: 6, probes: 6 }, 2: { beam: 14, probes: 9 }, 3: { beam: 32, probes: 12 } };

  function evaluateBoard(board, bonus, cfg) {
    let empty = 0, holes = 0, trans = 0, nearFull = 0, edgeTouch = 0, islands = 0;
    // rows/cols fill counts
    const rowFill = new Array(N).fill(0), colFill = new Array(N).fill(0);
    for (let r=0;r<N;r++) for (let c=0;c<N;c++) { const v = board[idx(r,c)]; if (v) { rowFill[r]++; colFill[c]++; } else empty++; }
    for (let r=0;r<N;r++) {
      for (let c=0;c<N;c++) {
        const i = idx(r,c), v = !!board[i];
        // transitions (fragmentation)
        if (c<N-1 && v !== !!board[idx(r,c+1)]) trans++;
        if (r<N-1 && v !== !!board[idx(r+1,c)]) trans++;
        if (!v) {
          // hole: empty cell whose 4 neighbours are all filled or walls
          const up = r===0 || board[idx(r-1,c)], dn = r===N-1 || board[idx(r+1,c)], lf = c===0 || board[idx(r,c-1)], rt = c===N-1 || board[idx(r,c+1)];
          const n = (up?1:0)+(dn?1:0)+(lf?1:0)+(rt?1:0);
          if (n===4) holes += 3; else if (n===3) holes += 1;   // dead-end cells are also bad
        } else {
          if (r===0||r===N-1||c===0||c===N-1) edgeTouch++;      // blocks hugging edges keep the center open
        }
      }
    }
    for (let k=0;k<N;k++) {
      // near-full lines are opportunities (7-8/9) but 8/9 with an awkward hole is risky, still positive
      if (rowFill[k] >= 7) nearFull += (rowFill[k]-6);
      if (colFill[k] >= 7) nearFull += (colFill[k]-6);
    }
    // placeability of probe pieces (survival + flexibility)
    let fit = 0;
    const probes = PROBES.slice(0, cfg.probes);
    for (const p of probes) { const n = E.allPlacements(board, p).length; fit += Math.min(n, 12) * (PROBE_W[p.key]||1) / 12; if (n===0) fit -= (PROBE_W[p.key]||1) * 2; }
    // small islands of filled blocks (isolated blobs are hard to clear)
    const seen = new Uint8Array(N*N);
    for (let i=0;i<N*N;i++) if (board[i] && !seen[i]) { let size=0; const st=[i]; seen[i]=1; while(st.length){ const j=st.pop(); size++; const r=(j/N)|0, c=j%N; const nb=[]; if(r>0)nb.push(j-N); if(r<N-1)nb.push(j+N); if(c>0)nb.push(j-1); if(c<N-1)nb.push(j+1); for(const k of nb) if(board[k]&&!seen[k]){seen[k]=1;st.push(k);} } if (size<=2) islands++; }

    // bonus cells still on board: keep them reachable (slight reward for lines near them being fillable)
    let bonusPot = 0;
    for (let i=0;i<N*N;i++) if (bonus[i]) { const r=(i/N)|0, c=i%N; bonusPot += bonus[i] * (Math.max(rowFill[r], colFill[c]) / N) * 0.02; }

    return empty * 1.0 - holes * 4.0 - trans * 0.9 + nearFull * 1.6 + edgeTouch * 0.25 + fit * 6 - islands * 2.5 + bonusPot;
  }

  function permutations(arr) {
    if (arr.length<=1) return [arr];
    const out=[]; arr.forEach((x,i)=>{ permutations([...arr.slice(0,i),...arr.slice(i+1)]).forEach(p=>out.push([x,...p])); }); return out;
  }

  /**
   * plan(board, bonus, pieces[3], state{streak,mult}, opts{level,bonusMode})
   * returns { moves:[{slot,r,c,points,lines}], total, eval, gameOver:boolean }
   */
  function plan(board, bonus, pieces, state, opts) {
    opts = opts || {}; const cfg = LEVELS[opts.level||2] || LEVELS[2]; const bonusMode = opts.bonusMode || 'clear';
    const slots = pieces.map((p,i)=>p?i:-1).filter(i=>i>=0);
    if (!slots.length) return { moves: [], total: 0, gameOver: false };
    const orders = permutations(slots);
    let best = null;
    const W_PTS = 1.0, W_MULT = 45;

    for (const order of orders) {
      // beam: each node = { board, bonus, streak, mult, pts, moves[] }
      let beam = [{ board, bonus, streak: state.streak||0, mult: state.mult||1, pts: 0, moves: [] }];
      for (let step=0; step<order.length; step++) {
        const slot = order[step]; const piece = pieces[slot]; const next = [];
        for (const node of beam) {
          const places = E.allPlacements(node.board, piece);
          for (const [r,c] of places) {
            const res = E.place(node.board, node.bonus, piece, r, c, { bonusMode });
            const sc = E.scoreMove(res, node);
            const pts = node.pts + sc.points;
            const heur = evaluateBoard(res.board, res.bonus, cfg) + (sc.mult - node.mult) * W_MULT;
            next.push({ board: res.board, bonus: res.bonus, streak: sc.streak, mult: sc.mult, pts, heur, score: pts*W_PTS + heur,
              moves: [...node.moves, { slot, r, c, points: sc.points, lines: sc.lines, rows: res.rows, cols: res.cols }] });
          }
        }
        if (!next.length) { beam = []; break; }
        next.sort((a,b)=>b.score-a.score);
        beam = next.slice(0, step===order.length-1 ? 1 : cfg.beam);
      }
      if (beam.length) {
        const cand = beam[0];
        // final survival check: is board still alive for a broad set of pieces? (already in heur via fit)
        if (!best || cand.score > best.score) best = cand;
      }
    }
    if (!best) return { moves: [], total: 0, gameOver: true };
    return { moves: best.moves, total: best.pts, eval: best.heur, score: best.score, gameOver: false, finalBoard: best.board };
  }

  /** Best single move for one piece (used for hints) */
  function bestSingle(board, bonus, piece, state, opts) {
    return plan(board, bonus, [piece], state, opts);
  }

  global.AI = { plan, bestSingle, evaluateBoard, LEVELS };
})(typeof self !== 'undefined' ? self : this);

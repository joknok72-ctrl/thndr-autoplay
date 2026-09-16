/* ===== THNDR AI v2 — SCORE-MAX planner (rules reverse-engineered from 26 real screenshots) =====
   REAL RULES:
     points(move) = (cubes + 20 × linesCleared + bonusCovered) × multAfter
     multAfter    = mult + orangeCubesCleared      (permanent; multiplies EVERYTHING that comes later)
     bonus cells pay when COVERED by a piece (they vanish), new ones spawn (max 3), values grow with level
     every round (3 pieces) = 1 level; game = 25 levels = 75 pieces; exactly one piece per round has an orange cube
   STRATEGY: maximise total score over the remaining horizon:
     immediate points + value of a higher multiplier for the rest of the game + survival (weighted by how
     much game is left and how big the multiplier is) + oranges parked in almost-complete lines.
*/
(function (global) {
  'use strict';
  const E = global.Engine; const N = E.N; const idx = E.idx;
  // probes = pieces that actually occur in the real game (no 3x3 / 5-long bars — they are never dealt)
  const PROBES = ['T5a','Lbig3','U1','plus','T4a','J4a','i4','S4a','sq2','v4','l3a','i3'].map(k => E.makePiece(k));
  const PROBE_W = { T5a: 2, Lbig3: 1.5, U1: 1.5, plus: 1.5, T4a: 1, J4a: 1, i4: 1, S4a: 1, sq2: 1, v4: 1, l3a: .6, i3: .6 };
  // full real piece library with deal weights (for survivability)
  const LIBP = E.LIB_ORDER.filter(k => (E.REAL_W||{})[k] > 0).map(k => ({ p: E.makePiece(k), w: E.REAL_W[k] }));
  const LIBW = LIBP.reduce((a,b)=>a+b.w,0) || 1;
  const LEVELS = { 1: { beam: 6, probes: 6 }, 2: { beam: 14, probes: 9 }, 3: { beam: 32, probes: 12 } };
  const W = Object.assign({ empty: 1, holes: 5, trans: .9, near: 1.6, edge: .25, fit: 6, isl: 2.5, sq3: 1.5, dead: 15,
              kMult: 18,      // value of +1 multiplier per remaining move (≈ average base points of a move)
              surv: 4.0,      // survival/board-quality weight (scaled by remaining moves)
              orange: 0.3, orangeMode: 0,    // oranges parked in near-complete lines (fraction of full mult value)
              bonusKeep: 0.4, farm: 1.2, farmRate: 0.33, // uncovered bonus cells: keep them coverable
              cover: 1, tight: 0.3, stake: 12, clean: 0,   // real-piece survivability (0 = off)
              riskBonus: 0, riskEnd: 0,   // extra stake: farmed bonus value / end bonus lost on death (0 = off)
              farmCubes0: 0, farmCubes1: 0, farmMin: 0.25,   // farming fades out between farmCubes0..farmCubes1 cubes (0 = off)
              dangerCubes: 99, dangerCover: 0, dangerM: 4, dangerK: 6,   // danger-triggered 1-round lookahead (off by default)
              endCube: 1000,  // REAL RULE: every cube still on the board when level 25 is completed pays 1000
              endFade: 9,     // the end-bonus fades in over the last N moves
              endBeam: 64,    // beam width used in the last endBeamRem moves (deeper end-game search)
              endBeamRem: 12,  // moves-remaining threshold that switches to endBeam
              midRollM: 0, midRollK: 6, midRollLevel: 1,   // mid-game 1-round lookahead (0 = off)
              rollRounds: 3, rollK: 5, rollKFill: 3, rollKPts: 2, rollM: 6, rollLevel: 1   // end-game rollouts (last N rounds)
            }, global.AI2_W || global.AI_W || {});

  /** Real THNDR scoring. */
  function realScore(res, mult) {
    const nm = mult + res.orangeCleared;
    return { points: (res.cells.length + 20 * res.lines + res.bonusHit) * nm, mult: nm, lines: res.lines };
  }

  function boardQuality(board, cfg) {
    let empty = 0, holes = 0, trans = 0, nearFull = 0, edgeTouch = 0, islands = 0;
    const rowFill = new Array(N).fill(0), colFill = new Array(N).fill(0);
    for (let r=0;r<N;r++) for (let c=0;c<N;c++) { if (board[idx(r,c)]) { rowFill[r]++; colFill[c]++; } else empty++; }
    for (let r=0;r<N;r++) for (let c=0;c<N;c++) {
      const i = idx(r,c), v = !!board[i];
      if (c<N-1 && v !== !!board[idx(r,c+1)]) trans++;
      if (r<N-1 && v !== !!board[idx(r+1,c)]) trans++;
      if (!v) {
        const up = r===0 || board[idx(r-1,c)], dn = r===N-1 || board[idx(r+1,c)], lf = c===0 || board[idx(r,c-1)], rt = c===N-1 || board[idx(r,c+1)];
        const n = (up?1:0)+(dn?1:0)+(lf?1:0)+(rt?1:0);
        if (n===4) holes += 3; else if (n===3) holes += 1;
      } else if (r===0||r===N-1||c===0||c===N-1) edgeTouch++;
    }
    const boxFill = new Array(9).fill(0);
    for (let k=0;k<N;k++) {
      if (rowFill[k] >= 7) nearFull += (rowFill[k]-6);
      if (colFill[k] >= 7) nearFull += (colFill[k]-6);
      const br=(k/3|0)*3, bc=(k%3)*3; let bf=0; for(let r=br;r<br+3;r++) for(let c=bc;c<bc+3;c++) if(board[idx(r,c)]) bf++;
      boxFill[k]=bf; if (bf >= 7) nearFull += (bf-6);
    }
    // REAL-PIECE SURVIVABILITY: what fraction (weighted by real deal frequency) of the pieces the game can deal still fits?
    let cover = 0, tight = 0;
    if (W.cover > 0) {
      for (const lp of LIBP) { const n = E.allPlacements(board, lp.p).length; if (n > 0) cover += lp.w; if (n < 3) tight += lp.w * (3 - n) / 3; }
      cover /= LIBW; tight /= LIBW;
    }
    let fit = 0, dead = 0;
    for (let k=0;k<Math.min(cfg.probes,PROBES.length);k++) { const p=PROBES[k]; const n = E.allPlacements(board, p).length; fit += Math.min(n, 12) * (PROBE_W[p.key]||1) / 12; if (n===0) { fit -= (PROBE_W[p.key]||1) * 2; dead++; } }
    let sq3 = 0;
    for (let r=0;r<=6;r++) for (let c=0;c<=6;c++) { let ok=true; for (let a=0;a<3&&ok;a++) for (let b=0;b<3;b++) if (board[idx(r+a,c+b)]) { ok=false; break; } if (ok) sq3++; }
    const seen = new Uint8Array(N*N);
    for (let i=0;i<N*N;i++) if (board[i] && !seen[i]) { let size=0; const st=[i]; seen[i]=1; while(st.length){ const j=st.pop(); size++; const r=(j/N)|0, c=j%N; if(r>0&&board[j-N]&&!seen[j-N]){seen[j-N]=1;st.push(j-N);} if(r<N-1&&board[j+N]&&!seen[j+N]){seen[j+N]=1;st.push(j+N);} if(c>0&&board[j-1]&&!seen[j-1]){seen[j-1]=1;st.push(j-1);} if(c<N-1&&board[j+1]&&!seen[j+1]){seen[j+1]=1;st.push(j+1);} } if (size<=2) islands++; }
    const q = empty * W.empty - holes * W.holes - trans * W.trans + nearFull * W.near + edgeTouch * W.edge + fit * W.fit - islands * W.isl + sq3 * W.sq3 - dead * W.dead;
    return { q, rowFill, colFill, boxFill, dead, cover, tight, empty };
  }

  /** Full evaluation of a node. ctx = { mult0, remaining } (remaining = moves left AFTER this node) */
  function evaluate(node, ctx, cfg) {
    const bq = boardQuality(node.board, cfg);
    const rem = Math.max(0, ctx.remaining);
    const multGain = (node.mult - ctx.mult0) * rem * W.kMult;
    // ORANGE POTENTIAL: an orange cube left on the board is worth (+1 mult × remaining moves × kMult) IF it gets cleared.
    // Its chance of being cleared soon depends on how many cells its best line still needs (1 cell ≈ next piece, 2–3 ≈ next round).
    // orangeMode 1 = feasibility curve over the 3 lines (row/col/box) instead of the old prog² on the best line.
    let orangePot = 0;
    const OF = W.orangeF || [0, 0.85, 0.6, 0.38, 0.2, 0.1, 0.05, 0.02, 0.01, 0];
    for (let i=0;i<N*N;i++) if (node.board[i] === 2) {
      const r=(i/N)|0, c=i%N, b=((r/3)|0)*3+((c/3)|0);
      if (W.orangeMode === 1) {
        const need = [9 - bq.rowFill[r], 9 - bq.colFill[c], 9 - bq.boxFill[b]].sort((a,b)=>a-b);
        // best line + a little for the second line (two ways to clear it)
        const f = Math.min(1, OF[need[0]] + 0.35 * OF[need[1]]);
        orangePot += rem * W.kMult * W.orange * f;
      } else {
        const prog = Math.max(bq.rowFill[r], bq.colFill[c], bq.boxFill[b]) / 9;
        orangePot += rem * W.kMult * W.orange * prog * prog;
      }
    }
    // BONUS CELLS: an uncovered cell is worth (value × mult) when eventually covered. REAL RULE: while 3 cells sit on the
    // board and none is covered during a round, one of them grows a tier each level (50→150→300→500→1K→2K).
    // farm > 0 turns on "bonus farming": value uncovered cells by their expected future value — they grow and the
    // multiplier grows, so covering later is worth more — as long as enough moves remain to cash them in.
    let bonusPot = 0;
    const TIER = [50,150,300,500,750,1000,1500,2000,3000,5000,7500,10000];
    let nB = 0, nCubes = 0; for (let i=0;i<N*N;i++) { if (node.bonus[i] && !node.board[i]) nB++; if (node.board[i]) nCubes++; }
    // SAFETY: 3 uncovered bonus cells lock up to 9 lines (their rows/cols/boxes cannot clear). On a crowded board that is
    // how games die — so the farming value fades out with crowding (the planner then cashes a cell in, which frees its lines).
    const farmScale = W.farmCubes1 > W.farmCubes0 ? Math.max(W.farmMin, Math.min(1, (W.farmCubes1 - nCubes) / (W.farmCubes1 - W.farmCubes0))) : 1;
    for (let i=0;i<N*N;i++) if (node.bonus[i] && !node.board[i]) {
      if (W.farm > 0) {
        const v = node.bonus[i]; let t = TIER.indexOf(v); if (t < 0) { t = 0; while (t < TIER.length-1 && TIER[t+1] <= v) t++; }
        // rounds left after this one; expected tier steps while farming ≈ (rounds × P(grow)/3 cells)  — capped
        const roundsLeft = rem / 3;
        const steps = nB >= 3 ? Math.min(TIER.length - 1 - t, roundsLeft * (W.farmRate||0.33)) : 0;
        const fut = t + steps; const lo = Math.floor(fut), hi = Math.min(TIER.length-1, lo+1); const fv = TIER[lo] + (TIER[hi]-TIER[lo])*(fut-lo);
        const multFut = node.mult + Math.min(roundsLeft, 25) * 0.8;      // multiplier keeps growing ~0.8/round
        const cash = rem >= 3 ? 1 : 0;                                   // must still have moves to cover it
        bonusPot += fv * multFut * W.farm * farmScale * cash * 0.3;
      } else {
        bonusPot += node.bonus[i] * node.mult * W.bonusKeep * (rem > 3 ? 0.3 : 0);
      }
    }
    const survW = W.surv * Math.max(W.survFloor||0, Math.min(1, rem / 15)) * (1 + node.mult * 0.15) * 4;
    // DYING = losing everything still to come (remaining moves × mult × ~12 pts) AND the 1000/cube end bonus.
    // survivability: (1-cover) is the chance the next dealt piece has NO place at all.
    // riskBonus/riskEnd: dying ALSO forfeits the farmed bonus cells and the 1000/cube end bonus — count them in the stake.
    let stake = rem > 0 ? rem * node.mult * (W.stake||12) : 0;
    if (rem > 0) stake += bonusPot * (W.riskBonus||0) + (W.riskEnd||0) * Math.min(1, (75 - rem) / 30);
    // CLEAN-BOARD PHASE (player's strategy): before the fill phase, reward an empty board — every empty cell keeps the
    // board flexible and every clear pays 20×mult; the reward scales with the multiplier (what a future line is worth).
    const cleanVal = rem >= W.endFade ? bq.empty * (W.clean||0) * (1 + node.mult * 0.15) : 0;
    const surviv = rem > 0 && W.cover > 0 ? -(1 - bq.cover) * stake * W.cover - bq.tight * stake * (W.tight||0) : 0;
    // END BONUS: 1000 per cube left on the board after the 75th piece (only if the game is completed).
    // Fades in over the last W.endFade moves; survival still matters until the very last move.
    let endVal = 0;
    if (rem < W.endFade) { let cubes = 0; for (let i=0;i<N*N;i++) if (node.board[i]) cubes++; const w = 1 - rem / W.endFade; endVal = cubes * W.endCube * w * w; }
    const deadPen = (bq.dead > 0 && rem > 0) ? (W.deadPen||400) * (1 + node.mult*0.2) * (rem < W.endFade ? 6 : 1) : 0;
    return node.pts + multGain + orangePot + bonusPot + bq.q * survW + endVal - deadPen + surviv + cleanVal;
  }

  function permutations(arr) { if (arr.length<=1) return [arr]; const out=[]; arr.forEach((x,i)=>{ permutations([...arr.slice(0,i),...arr.slice(i+1)]).forEach(p=>out.push([x,...p])); }); return out; }

  /** plan(board, bonus, pieces[3], state{mult, level}, opts{level}) */
  function plan(board, bonus, pieces, state, opts) {
    opts = opts || {}; const cfg = LEVELS[opts.level||3] || LEVELS[3];
    // strategy knobs (per call): fill horizon (moves) and clean-board style
    if (opts.fillMoves) W.endFade = Math.max(3, Math.min(15, +opts.fillMoves));
    if (opts.clean !== undefined) W.clean = opts.clean ? 1 : 0;
    const slots = pieces.map((p,i)=>p?i:-1).filter(i=>i>=0);
    if (!slots.length) return { moves: [], total: 0, gameOver: false };
    const mult0 = state.mult || 1; const lvl = Math.min(25, Math.max(1, state.level || 1));
    const remAfterRound = (25 - lvl) * 3;
    const roundsLeft = 25 - lvl;                 // full rounds after this one
    const deep = opts.deep !== false && W.deep !== false;
    const rollActive = deep && opts.rollout !== false && W.rollRounds > 0 && roundsLeft >= 0 && roundsLeft < W.rollRounds;
    let best = null; const cands = [];
    for (const order of permutations(slots)) {
      let beam = [{ board, bonus, mult: mult0, pts: 0, moves: [] }];
      for (let step=0; step<order.length; step++) {
        const slot = order[step]; const piece = pieces[slot]; const next = [];
        const remaining = remAfterRound + (order.length - 1 - step);
        for (const node of beam) for (const [r,c] of E.allPlacements(node.board, piece)) {
          const res = E.place(node.board, node.bonus, piece, r, c, { bonusMode: 'cover' });
          const sc = realScore(res, node.mult);
          const nn = { board: res.board, bonus: res.bonus, mult: sc.mult, pts: node.pts + sc.points,
            moves: [...node.moves, { slot, r, c, points: sc.points, lines: sc.lines, rows: res.rows, cols: res.cols, boxes: res.boxes }] };
          nn.score = evaluate(nn, { mult0, remaining }, cfg);
          next.push(nn);
        }
        if (!next.length) { beam = []; break; }
        next.sort((a,b)=>b.score-a.score);
        beam = next.slice(0, step===order.length-1 ? (rollActive ? W.rollK : 1) : ((deep && remaining <= W.endBeamRem) ? Math.max(cfg.beam, W.endBeam) : cfg.beam));
      }
      if (beam.length) { for (const b of beam) cands.push(b); if (!best || beam[0].score > best.score) best = beam[0]; }
    }
    if (!best) return { moves: [], total: 0, gameOver: true };
    // ---- end-game rollouts: in the last W.rollRounds rounds, re-rank the top candidates by simulating the
    //      remaining rounds with random pieces (expectimax over the unknown future) ----
    if (rollActive && cands.length > 1) {
      // candidate diversity: the heuristic top-K PLUS the best "fill" plans (pts + cubes×1000) and the best raw-points plans,
      // so the rollouts (which know the real end-bonus) can pick a board-filling line the heuristic under-rates.
      cands.sort((a,b)=>b.score-a.score);
      const top = cands.slice(0, W.rollK);
      const fillVal = n => { let c=0; for (let i=0;i<N*N;i++) if (n.board[i]) c++; return n.pts + c * W.endCube; };
      const byFill = cands.slice().sort((a,b)=>fillVal(b)-fillVal(a));
      for (const n of byFill) { if (top.length >= W.rollK + (W.rollKFill||3)) break; if (!top.includes(n)) top.push(n); }
      const byPts = cands.slice().sort((a,b)=>b.pts-a.pts);
      for (const n of byPts) { if (top.length >= W.rollK + (W.rollKFill||3) + (W.rollKPts||2)) break; if (!top.includes(n)) top.push(n); }
      let rs = 12345 + lvl * 7919; const R = () => { rs = (rs * 1664525 + 1013904223) >>> 0; return rs / 4294967296; };
      // same random future for every candidate (common random numbers → fair comparison)
      const futures = [];
      for (let m=0;m<W.rollM;m++) { const f=[]; for (let k=0;k<roundsLeft;k++) { const ps=[0,1,2].map(()=>{ const p=E.randomPiece(R); p.cells.forEach(c=>c.v=1); return p; }); const op=ps[Math.floor(R()*3)]; op.cells[Math.floor(R()*op.cells.length)].v=2; f.push(ps); } futures.push(f); }
      // every candidate is evaluated on ALL futures (no time budget)
      const sums = new Array(top.length).fill(0); let used = 0;
      for (let m=0;m<futures.length;m++) {
        const f = futures[m]; const part = new Array(top.length).fill(0);
        for (let ci=0; ci<top.length; ci++) {
          const cand = top[ci];
          let b = cand.board, bo = cand.bonus, mu = cand.mult, pts = cand.pts, dead = false;
          for (let k=0;k<roundsLeft && !dead;k++) {
            const pl = plan(b, bo, f[k], { mult: mu, level: lvl + 1 + k }, { level: W.rollLevel, rollout: false, deep: false });
            if (pl.gameOver) { dead = true; break; }
            b = pl.finalBoard; bo = pl.finalBonus; mu = pl.finalMult; pts += pl.total;
          }
          if (!dead) { let cubes = 0; for (let i=0;i<N*N;i++) if (b[i]) cubes++; pts += cubes * 1000; }
          part[ci] = pts;
        }
        for (let ci=0; ci<top.length; ci++) sums[ci] += part[ci];
        used++;
      }
      let bestAvg = -Infinity, bestNode = null;
      for (let ci=0; ci<top.length; ci++) if (used > 0 && sums[ci] / used > bestAvg) { bestAvg = sums[ci] / used; bestNode = top[ci]; }
      if (bestNode) best = bestNode;
    }
    // ---- MID-GAME LOOKAHEAD (orange / multiplier): outside the end-game, re-rank the top candidates by a 1-round
    //      rollout with random next pieces (fast planner) + the heuristic value of the resulting position.
    //      This is what tells apart "orange parked where the next round can clear it" from "orange buried".
    let danger = false;
    if (!rollActive && opts.rollout !== false && deep && roundsLeft >= 1 && cands.length > 1 && (W.midRollM||0) === 0) {
      let cubes0 = 0; for (let i=0;i<N*N;i++) if (board[i]) cubes0++;
      let cov = 1; if (W.dangerCover > 0) cov = boardQuality(best.board, cfg).cover;
      danger = cubes0 >= W.dangerCubes || cov < W.dangerCover;
    }
    const midActive = !rollActive && opts.rollout !== false && deep && ((W.midRollM||0) > 0 || danger) && roundsLeft >= 1 && cands.length > 1;
    if (midActive) {
      const MM = danger ? (W.dangerM||4) : W.midRollM, KK = danger ? (W.dangerK||6) : (W.midRollK||6);
      cands.sort((a,b)=>b.score-a.score);
      const top = cands.slice(0, KK);
      let rs = 777 + lvl * 104729; const R = () => { rs = (rs * 1664525 + 1013904223) >>> 0; return rs / 4294967296; };
      const futures = [];
      for (let m=0;m<MM;m++) { const ps=[0,1,2].map(()=>{ const p=E.randomPiece(R); p.cells.forEach(c=>c.v=1); return p; }); const op=ps[Math.floor(R()*3)]; op.cells[Math.floor(R()*op.cells.length)].v=2; futures.push(ps); }
      const rem2 = Math.max(0, remAfterRound - 3);
      let bestAvg = -Infinity, bestNode = null;
      for (const cand of top) {
        let sum = 0;
        for (const f of futures) {
          const pl = plan(cand.board, cand.bonus, f, { mult: cand.mult, level: lvl + 1 }, { level: W.midRollLevel||1, rollout: false, deep: false });
          if (pl.gameOver) { sum += cand.pts - rem2 * cand.mult * (W.stake||12); continue; }
          const nn = { board: pl.finalBoard, bonus: pl.finalBonus, mult: pl.finalMult, pts: cand.pts + pl.total };
          sum += evaluate(nn, { mult0, remaining: rem2 }, cfg);
        }
        const avg = sum / futures.length;
        if (avg > bestAvg) { bestAvg = avg; bestNode = cand; }
      }
      if (bestNode) best = bestNode;
    }
    return { moves: best.moves, total: best.pts, score: best.score, gameOver: false, finalBoard: best.board, finalBonus: best.bonus, finalMult: best.mult };
  }
  global.AI = { plan, evaluate, realScore, LEVELS, W, bestSingle: (b,bo,p,st,o)=>plan(b,bo,[p],st,o), evaluateBoard: (b)=>boardQuality(b, LEVELS[3]).q };
  global.AI2 = global.AI;
})(typeof self !== 'undefined' ? self : this);

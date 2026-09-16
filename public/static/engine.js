/* ===== THNDR AI Block — Game Engine (pure logic, no DOM) =====
   Board: 9x9. Cell values: 0 empty, 1 blue block, 2 orange (multiplier) block.
   bonus[]: number per cell (0 = none). A bonus cell is EMPTY and legal to place on.
   Pieces: { cells:[{r,c,v}], yv:number }  (v: 1 blue, 2 orange; yv = multiplier gain of orange cubes)
*/
(function (global) {
  'use strict';
  const N = 9;

  // ---------- Piece library (shapes seen in the screenshots + classic block-blast set) ----------
  const SHAPES = {
    dot: [[0,0]],
    i2: [[0,0],[0,1]], i3: [[0,0],[0,1],[0,2]], i4: [[0,0],[0,1],[0,2],[0,3]], i5: [[0,0],[0,1],[0,2],[0,3],[0,4]],
    v2: [[0,0],[1,0]], v3: [[0,0],[1,0],[2,0]], v4: [[0,0],[1,0],[2,0],[3,0]], v5: [[0,0],[1,0],[2,0],[3,0],[4,0]],
    sq2: [[0,0],[0,1],[1,0],[1,1]], sq3: [[0,0],[0,1],[0,2],[1,0],[1,1],[1,2],[2,0],[2,1],[2,2]],
    l3a: [[0,0],[1,0],[1,1]], l3b: [[0,0],[0,1],[1,0]], l3c: [[0,0],[0,1],[1,1]], l3d: [[0,1],[1,0],[1,1]],
    L4a: [[0,0],[1,0],[2,0],[2,1]], L4b: [[0,0],[0,1],[1,0],[2,0]], L4c: [[0,0],[0,1],[0,2],[1,0]], L4d: [[0,2],[1,0],[1,1],[1,2]],
    J4a: [[0,1],[1,1],[2,0],[2,1]], J4b: [[0,0],[1,0],[1,1],[1,2]], J4c: [[0,0],[0,1],[1,1],[2,1]], J4d: [[0,0],[0,1],[0,2],[1,2]],
    Lbig1: [[0,0],[1,0],[2,0],[2,1],[2,2]], Lbig2: [[0,0],[0,1],[0,2],[1,0],[2,0]], Lbig3: [[0,0],[0,1],[0,2],[1,2],[2,2]], Lbig4: [[0,2],[1,2],[2,0],[2,1],[2,2]],
    T4a: [[0,0],[0,1],[0,2],[1,1]], T4b: [[0,1],[1,0],[1,1],[1,2]], T4c: [[0,0],[1,0],[1,1],[2,0]], T4d: [[0,1],[1,0],[1,1],[2,1]],
    S4a: [[0,1],[0,2],[1,0],[1,1]], S4b: [[0,0],[1,0],[1,1],[2,1]], Z4a: [[0,0],[0,1],[1,1],[1,2]], Z4b: [[0,1],[1,0],[1,1],[2,0]],
    plus: [[0,1],[1,0],[1,1],[1,2],[2,1]],
    U1: [[0,0],[0,2],[1,0],[1,1],[1,2]], U2: [[0,0],[0,1],[0,2],[1,0],[1,2]], U3: [[0,0],[0,1],[1,0],[2,0],[2,1]], U4: [[0,0],[0,1],[1,1],[2,0],[2,1]],
    d2a: [[0,1],[1,0]], d2b: [[0,0],[1,1]], d3a: [[0,2],[1,1],[2,0]], d3b: [[0,0],[1,1],[2,2]],
    stair: [[0,2],[1,1],[1,2],[2,0],[2,1]],
    rect23: [[0,0],[0,1],[0,2],[1,0],[1,1],[1,2]], rect32: [[0,0],[0,1],[1,0],[1,1],[2,0],[2,1]],
    // shapes observed in the REAL game (74-frame + 26-frame analyses): long-stem T (4 rotations), 5-cell S/Z, 4-diagonal
    T5a: [[0,0],[0,1],[0,2],[1,1],[2,1]], T5b: [[0,2],[1,0],[1,1],[1,2],[2,2]], T5c: [[0,0],[1,0],[1,1],[1,2],[2,0]], T5d: [[0,1],[1,1],[2,0],[2,1],[2,2]],
    S5a: [[0,1],[0,2],[1,1],[2,0],[2,1]], S5b: [[0,0],[1,0],[1,1],[1,2],[2,2]], d4: [[0,0],[1,1],[2,2],[3,3]],
    d4b: [[0,3],[1,2],[2,1],[3,0]], d5: [[0,0],[1,1],[2,2],[3,3],[4,4]], d5b: [[0,4],[1,3],[2,2],[3,1],[4,0]], S5c: [[0,0],[0,1],[1,1],[2,1],[2,2]], S5d: [[0,2],[1,0],[1,1],[1,2],[2,0]],
  };
  const LIB_ORDER = ['dot','i2','v2','i3','v3','i4','v4','i5','v5','sq2','l3a','l3b','l3c','l3d','L4a','L4b','L4c','L4d','J4a','J4b','J4c','J4d','T4a','T4b','T4c','T4d','S4a','S4b','Z4a','Z4b','Lbig1','Lbig2','Lbig3','Lbig4','plus','U1','U2','U3','U4','d2a','d2b','d3a','d3b','stair','rect23','rect32','sq3','T5a','T5b','T5c','T5d','S5a','S5b','d4','d4b','d5','d5b','S5c','S5d'];

  function makePiece(shapeKey, opts) {
    const cells = SHAPES[shapeKey].map(([r,c]) => ({ r, c, v: 1 }));
    const p = { cells, yv: 1, key: shapeKey };
    if (opts && opts.orangeIdx != null && cells[opts.orangeIdx]) cells[opts.orangeIdx].v = 2;
    return normalize(p);
  }
  function normalize(p) {
    if (!p.cells.length) return p;
    const minR = Math.min(...p.cells.map(c => c.r)), minC = Math.min(...p.cells.map(c => c.c));
    p.cells = p.cells.map(c => ({ r: c.r - minR, c: c.c - minC, v: c.v })).sort((a,b)=>a.r-b.r||a.c-b.c);
    p.h = Math.max(...p.cells.map(c=>c.r)) + 1; p.w = Math.max(...p.cells.map(c=>c.c)) + 1;
    return p;
  }
  function rotate(p) { const h = p.h; return normalize({ ...p, cells: p.cells.map(c => ({ r: c.c, c: h - 1 - c.r, v: c.v })) }); }
  function flip(p) { const w = p.w; return normalize({ ...p, cells: p.cells.map(c => ({ r: c.r, c: w - 1 - c.c, v: c.v })) }); }

  // REAL deal frequencies, fitted on 660 pieces from 11 logged games: the game deals ~50 shapes almost UNIFORMLY
  // (each 1–4%). Counts below are the observed numbers (unseen-but-plausible shapes get 1).
  const REAL_W = { dot: 16, i2: 18, v2: 20, i3: 12, v3: 24, i4: 13, v4: 16, i5: 7, v5: 19, sq2: 15,
    l3a: 10, l3b: 17, l3c: 9, l3d: 16, L4a: 1, L4b: 13, L4c: 1, L4d: 1, J4a: 15, J4b: 16, J4c: 1, J4d: 8,
    T4a: 18, T4b: 15, T4c: 26, T4d: 8, S4a: 11, S4b: 14, Z4a: 15, Z4b: 14, Lbig1: 12, Lbig2: 16, Lbig3: 18, Lbig4: 27,
    plus: 10, U1: 15, U2: 13, U3: 10, U4: 15, d2a: 9, d2b: 18, d3a: 10, d3b: 15, d4: 8, d4b: 6, d5: 3, d5b: 1,
    T5a: 21, T5b: 16, T5c: 10, T5d: 15, S5a: 1, S5b: 4, S5c: 2, S5d: 1 };
  function randomPiece(rng) {
    rng = rng || Math.random;
    // REAL distribution (96 pieces observed across two full games). sq3 / i5 / v5 / 2x3 rects / stair were NEVER dealt.
    const weights = LIB_ORDER.map(k => REAL_W[k] || 0);
    const total = weights.reduce((a,b)=>a+b,0); let x = rng()*total, key = LIB_ORDER[0];
    for (let i=0;i<weights.length;i++){ x -= weights[i]; if (x<=0){ key = LIB_ORDER[i]; break; } }
    const p = makePiece(key);
    if (rng() < 0.3) { p.cells[Math.floor(rng()*p.cells.length)].v = 2; }
    return p;
  }

  // ---------- Board ----------
  function emptyBoard() { return new Uint8Array(N*N); }
  function idx(r,c){ return r*N+c; }

  function canPlace(board, piece, r0, c0) {
    if (r0 < 0 || c0 < 0 || r0 + piece.h > N || c0 + piece.w > N) return false;
    for (const c of piece.cells) if (board[idx(r0+c.r, c0+c.c)]) return false;
    return true;
  }
  function anyPlacement(board, piece) {
    for (let r=0;r<=N-piece.h;r++) for (let c=0;c<=N-piece.w;c++) if (canPlace(board,piece,r,c)) return true;
    return false;
  }
  function allPlacements(board, piece) {
    const out = [];
    for (let r=0;r<=N-piece.h;r++) for (let c=0;c<=N-piece.w;c++) if (canPlace(board,piece,r,c)) out.push([r,c]);
    return out;
  }

  /** Apply a placement. Returns { board, bonus, cleared:{rows,cols}, orangeCleared, yvGain, bonusHit, cells:[idx], clearedIdx:[idx] } */
  function place(board, bonus, piece, r0, c0, opts) {
    opts = opts || {};
    const nb = board.slice(); const nbonus = bonus.slice();
    const cells = [];
    let bonusCovered = 0;
    for (const c of piece.cells) { const i = idx(r0+c.r, c0+c.c); nb[i] = c.v; cells.push(i); if (nbonus[i]) { bonusCovered += nbonus[i]; if (opts.bonusMode === 'cover') nbonus[i] = 0; } }
    const rows = [], cols = [], boxes = [];
    for (let r=0;r<N;r++){ let full=true; for(let c=0;c<N;c++) if(!nb[idx(r,c)]){full=false;break;} if(full) rows.push(r); }
    for (let c=0;c<N;c++){ let full=true; for(let r=0;r<N;r++) if(!nb[idx(r,c)]){full=false;break;} if(full) cols.push(c); }
    // 3x3 boxes (Sudoku-style) also clear when complete — as in the original THNDR game
    for (let b=0;b<9;b++){ const br=(b/3|0)*3, bc=(b%3)*3; let full=true; for(let r=br;r<br+3&&full;r++) for(let c=bc;c<bc+3;c++) if(!nb[idx(r,c)]){full=false;break;} if(full) boxes.push(b); }
    const clearedIdx = new Set(); let orangeCleared = 0, bonusHit = 0;
    for (const r of rows) for (let c=0;c<N;c++) clearedIdx.add(idx(r,c));
    for (const c of cols) for (let r=0;r<N;r++) clearedIdx.add(idx(r,c));
    for (const b of boxes) { const br=(b/3|0)*3, bc=(b%3)*3; for(let r=br;r<br+3;r++) for(let c=bc;c<bc+3;c++) clearedIdx.add(idx(r,c)); }
    for (const i of clearedIdx) { if (nb[i]===2) orangeCleared++; if (nbonus[i]) { bonusHit += nbonus[i]; nbonus[i] = 0; } nb[i] = 0; }
    if (opts.bonusMode === 'cover') bonusHit = bonusCovered;
    return { board: nb, bonus: nbonus, rows, cols, boxes, lines: rows.length+cols.length+boxes.length, orangeCleared, yvGain: orangeCleared * (piece.yv||1), bonusHit, cells, clearedIdx: [...clearedIdx] };
  }

  /** REAL THNDR scoring (reverse-engineered from 26 consecutive screenshots):
   *   points = (cubes + 20·linesCleared + bonusCovered) × (mult + orangeCubesCleared); multiplier is permanent. */
  function scoreMove(res, state) {
    const mult = (state.mult || 1) + res.orangeCleared;
    const pts = (res.cells.length + 20 * res.lines + (res.bonusHit || 0)) * mult;
    return { points: pts, streak: res.lines > 0 ? (state.streak || 0) + 1 : 0, mult, cubes: res.cells.length, lines: res.lines };
  }

  const Engine = { N, SHAPES, LIB_ORDER, REAL_W, makePiece, normalize, rotate, flip, randomPiece, emptyBoard, idx, canPlace, anyPlacement, allPlacements, place, scoreMove };
  global.Engine = Engine;
})(typeof self !== 'undefined' ? self : this);

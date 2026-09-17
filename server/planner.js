// Loads the exact same engine.js + ai.js used by the website and exposes plan() natively (no vm — ~25x faster).
const fs = require('fs'), path = require('path');
const STATIC = path.join(__dirname, '..', 'public', 'static');
const g = { console };
g.self = g; g.window = g; g.global = g;
for (const f of ['engine.js', 'ai.js']) {
  const src = fs.readFileSync(path.join(STATIC, f), 'utf8');
  new Function('self', 'global', 'window', 'console', src)(g, g, g, console);
}
const { Engine, AI } = g;
function normPiece(p) {
  if (!p || !Array.isArray(p.cells) || !p.cells.length) return null;
  const cells = p.cells.map(c => ({ r: c.r | 0, c: c.c | 0, v: c.v === 2 ? 2 : 1 }));
  return Engine.normalize ? Engine.normalize({ cells, yv: p.yv | 0 || 1 }) : { cells, yv: p.yv | 0 || 1 };
}
function plan(req) {
  const board = Array.from(req.board || [], x => x | 0), bonus = Array.from(req.bonus || [], x => x | 0);
  if (board.length !== 81 || bonus.length !== 81) throw new Error('board/bonus must have 81 cells');
  const pieces = [0, 1, 2].map(i => normPiece((req.pieces || [])[i]));
  const level = Math.min(4, Math.max(1, req.level | 0 || 4));
  const state = { mult: req.mult | 0 || 1, level: req.gameLevel | 0 || 1, score: req.score | 0 };
  const opts = { level, deep: req.deep !== false, rollout: req.rollout !== false };
  if (req.fillMoves) opts.fillMoves = req.fillMoves; if (req.clean !== undefined) opts.clean = req.clean;
  const t0 = Date.now();
  const r = AI.plan(board, bonus, pieces, state, opts);
  return { moves: (r.moves || []).map(m => ({ slot: m.slot, r: m.r, c: m.c, points: m.points | 0, lines: m.lines | 0 })),
           total: r.total | 0, gameOver: !!r.gameOver, finalMult: r.finalMult || state.mult, trayRisk: r.trayRisk, level, ms: Date.now() - t0 };
}
module.exports = { plan, Engine, AI };

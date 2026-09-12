/* Web Worker wrapper so heavy planning never freezes the phone UI */
importScripts('/static/engine.js?v=2', '/static/ai.js?v=2');
self.onmessage = function (e) {
  const { id, board, bonus, pieces, state, opts } = e.data;
  try {
    const b = Uint8Array.from(board);
    const bo = Array.from(bonus);
    const res = AI.plan(b, bo, pieces, state, opts);
    if (res.finalBoard) res.finalBoard = Array.from(res.finalBoard);
    self.postMessage({ id, ok: true, res });
  } catch (err) {
    self.postMessage({ id, ok: false, error: String(err) });
  }
};

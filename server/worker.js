const { parentPort, workerData } = require('worker_threads');
const { plan } = require('./planner');
try { parentPort.postMessage({ ok: true, result: plan(workerData) }); }
catch (e) { parentPort.postMessage({ ok: false, error: String(e && e.message || e) }); }

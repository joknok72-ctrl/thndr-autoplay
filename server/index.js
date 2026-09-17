// THNDR plan server — POST /api/plan  { board[81], bonus[81], pieces[3], mult, gameLevel, score, level(1-4) }
const http = require('http'); const { Worker } = require('worker_threads'); const os = require('os'); const path = require('path');
const PORT = +process.env.PORT || 8080, MAX_MS = +process.env.MAX_MS || 240000, MAX_PAR = +process.env.MAX_PAR || Math.max(1, os.cpus().length);
const KEY = process.env.PLAN_KEY || '';
// Fly TRIAL machines are force-stopped every 5 minutes (until a card is added). A stop mid-plan drops the request, so:
// when uptime gets close to the limit and nothing is running, answer 503 {retry:true} and exit — the proxy restarts the
// machine on the client's retry (~2 s) with a fresh 5-minute window. Set TRIAL_MS=0 once the account is upgraded.
const START = Date.now(), TRIAL_MS = process.env.TRIAL_MS === undefined ? 300000 : +process.env.TRIAL_MS, GUARD_MS = +process.env.GUARD_MS || 100000;
const nearStop = () => TRIAL_MS > 0 && (Date.now() - START) > (TRIAL_MS - GUARD_MS);
let running = 0; const queue = [];
function runPlan(body) {
  return new Promise((resolve, reject) => {
    const start = () => {
      running++;
      const w = new Worker(path.join(__dirname, 'worker.js'), { workerData: body, resourceLimits: { maxOldGenerationSizeMb: +process.env.WORKER_MB || 1400 } });
      const to = setTimeout(() => { w.terminate(); reject(new Error('timeout')); }, MAX_MS);
      w.once('message', m => { clearTimeout(to); m.ok ? resolve(m.result) : reject(new Error(m.error)); w.terminate(); });
      w.once('error', e => { clearTimeout(to); reject(e); });
      w.once('exit', () => { running--; if (queue.length) queue.shift()(); });
    };
    if (running < MAX_PAR) start(); else queue.push(start);
  });
}
const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*' }); res.end(JSON.stringify(obj)); };
http.createServer((req, res) => {
  if (req.method === 'OPTIONS') return json(res, 204, {});
  if (req.method === 'GET' && (req.url === '/' || req.url === '/health')) return json(res, 200, { ok: true, service: 'thndr-plan', cpus: os.cpus().length, running, queued: queue.length, uptimeS: Math.round((Date.now() - START) / 1000), trial: TRIAL_MS > 0, version: require('./package.json').version });
  if (req.method === 'POST' && req.url.startsWith('/api/plan')) {
    if (KEY && req.headers['x-plan-key'] !== KEY) return json(res, 401, { error: 'bad key' });
    if (nearStop() && running === 0) { json(res, 503, { retry: true, reason: 'recycling before trial stop' }); setTimeout(() => process.exit(0), 300); return; }
    let data = ''; req.on('data', c => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', async () => {
      try { const body = JSON.parse(data); const t0 = Date.now(); const r = await runPlan(body); r.wallMs = Date.now() - t0; json(res, 200, r); }
      catch (e) { json(res, e.message === 'timeout' ? 504 : 400, { error: e.message }); }
    });
    return;
  }
  json(res, 404, { error: 'not found' });
}).listen(PORT, '0.0.0.0', () => console.log('thndr-plan listening on', PORT, 'cpus', os.cpus().length));

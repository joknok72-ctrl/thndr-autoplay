import { Hono } from 'hono'
import { serveStatic } from 'hono/cloudflare-workers'

const app = new Hono()

// Static assets (public/static/* -> /static/*)
app.use('/static/*', serveStatic({ root: './public' }))
app.get('/manifest.json', serveStatic({ path: './public/manifest.json' }))
app.get('/sw.js', serveStatic({ path: './public/sw.js' }))

// Tiny health/API endpoints (the game runs fully client-side)
app.get('/api/health', (c) => c.json({ ok: true, game: 'THNDR AI Block', levels: 25 }))

// Plan-server proxy: some mobile networks cannot reach fly.dev directly, so the app can call the game domain instead
const PLAN_SERVER = 'https://thndr-plan.fly.dev'
app.get('/health', async (c) => {
  try { const r = await fetch(PLAN_SERVER + '/health', { signal: AbortSignal.timeout(20000) }); return new Response(r.body, { status: r.status, headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' } }) }
  catch (e) { return c.json({ ok: false, error: String(e) }, 502) }
})
app.post('/api/plan', async (c) => {
  try {
    const r = await fetch(PLAN_SERVER + '/api/plan', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: await c.req.text(), signal: AbortSignal.timeout(280000) })
    return new Response(r.body, { status: r.status, headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' } })
  } catch (e) { return c.json({ error: String(e) }, 502) }
})

app.get('/', (c) => {
  return c.html(`<!DOCTYPE html>
<html lang="ar">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
  <meta name="theme-color" content="#081f3d">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <meta name="mobile-web-app-capable" content="yes">
  <title>THNDR AI Block</title>
  <link rel="manifest" href="/manifest.json">
  <link rel="icon" href="/static/icon-192.png">
  <link rel="apple-touch-icon" href="/static/icon-192.png">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Nunito:wght@700;800;900&family=Cairo:wght@600;700;800&display=swap" rel="stylesheet">
  <link href="https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@6.5.2/css/all.min.css" rel="stylesheet">
  <link href="/static/style.css?v=3" rel="stylesheet">
</head>
<body data-theme="blue">
  <div id="app-root">

    <!-- ===== Top bar ===== -->
    <header id="topbar" class="topbar" dir="ltr">
      <button id="menu-btn" class="menu-btn" aria-label="القائمة"><i class="fa-solid fa-bars"></i></button>
      <nav class="pills">
        <button class="pill stat-pill" id="pill-best" data-stat="best"><i class="fa-solid fa-bolt"></i><span>0</span><em class="dot"></em><b class="plus">+</b></button>
        <button class="pill stat-pill" id="pill-lines" data-stat="lines"><i class="fa-solid fa-cube"></i><span>0</span><b class="plus">+</b></button>
        <button class="pill stat-pill" id="pill-games" data-stat="games"><i class="fa-solid fa-ticket"></i><span>0</span></button>
        <button class="pill stat-pill" id="pill-wins" data-stat="wins"><i class="fa-brands fa-bitcoin"></i><span>0</span><em class="dot"></em></button>
      </nav>
    </header>

    <!-- ===== Tool buttons ===== -->
    <section id="tools" class="tools" dir="ltr">
      <button class="tool-btn" id="btn-new" aria-label="لعبة جديدة"><i class="fa-solid fa-rotate"></i></button>
      <button class="tool-btn" id="btn-plan" aria-label="خطة الذكاء الاصطناعي"><i class="fa-solid fa-wand-magic-sparkles"></i></button>
      <button class="tool-btn" id="btn-random" aria-label="قطع عشوائية"><i class="fa-solid fa-dice"></i><em class="dot pink"></em><em class="sq green"></em></button>
      <button class="tool-btn" id="btn-theme" aria-label="الألوان"><i class="fa-solid fa-palette"></i></button>
      <button class="tool-btn" id="btn-settings" aria-label="الإعدادات"><i class="fa-solid fa-gear"></i></button>
    </section>

    <!-- ===== Stats row ===== -->
    <section class="stats-row" dir="ltr">
      <div class="pill wide"><i class="fa-solid fa-ticket"></i><span id="streak-val">0</span></div>
      <div class="pill wide right"><i class="fa-solid fa-trophy"></i><span id="score-val">0</span></div>
    </section>

    <!-- ===== Board ===== -->
    <main id="board-wrap" class="board-wrap" dir="ltr">
      <div id="board" class="board" role="grid"></div>
      <div id="fx-layer" class="fx-layer"></div>
    </main>

    <!-- ===== Level row ===== -->
    <section class="stats-row" dir="ltr">
      <div class="pill wide"><span class="mini-block orange"></span><span id="mult-val">1X</span></div>
      <div class="pill wide right"><span class="level-label">LEVEL <span id="level-val">1</span>/25</span></div>
    </section>

    <div class="logo" aria-hidden="true">THNDR</div>

    <!-- ===== Tray ===== -->
    <section id="tray" class="tray" dir="ltr">
      <button class="slot" data-slot="0" aria-label="القطعة 1"></button>
      <button class="slot" data-slot="1" aria-label="القطعة 2"></button>
      <button class="slot" data-slot="2" aria-label="القطعة 3"></button>
    </section>

    <!-- ===== Action bar ===== -->
    <section id="actions" class="actions">
      <button id="act-undo" class="act ghost"><i class="fa-solid fa-rotate-left"></i> تراجع</button>
      <button id="act-plan" class="act primary"><i class="fa-solid fa-brain"></i> خطة الذكاء</button>
      <button id="act-play" class="act success"><i class="fa-solid fa-play"></i> تنفيذ</button>
    </section>
    <p id="hint-text" class="hint-text">اضغط على أي خانة من الثلاث تحت لرسم القطعة، أو اضغط خانة فاضية في اللوحة لوضع رقم مكافأة.</p>
  </div>

  <!-- ===== Sheets / overlays ===== -->
  <div id="overlay" class="overlay hidden"></div>

  <!-- Piece editor -->
  <section id="sheet-piece" class="sheet hidden" dir="rtl">
    <header class="sheet-head"><h2>رسم القطعة <span id="pe-title"></span></h2><button class="close" data-close><i class="fa-solid fa-xmark"></i></button></header>
    <div class="pe-body">
      <div class="pe-grid-wrap"><div id="pe-grid" class="pe-grid" dir="ltr"></div></div>
      <div class="pe-side">
        <div class="pe-palette">
          <button class="pal blue active" data-paint="1"><span class="mini-block blue"></span>أزرق</button>
          <button class="pal orange" data-paint="2"><span class="mini-block orange"></span>أصفر</button>
          <button class="pal erase" data-paint="0"><i class="fa-solid fa-eraser"></i>مسح</button>
        </div>
        <div class="pe-yval">
          <label>قيمة الأصفر</label>
          <div class="stepper"><button data-yv="-1">−</button><span id="pe-yval">+1</span><button data-yv="1">+</button></div>
        </div>
        <div class="pe-tools">
          <button id="pe-rot"><i class="fa-solid fa-rotate-right"></i></button>
          <button id="pe-flip"><i class="fa-solid fa-left-right"></i></button>
          <button id="pe-clear"><i class="fa-solid fa-trash"></i></button>
        </div>
      </div>
    </div>
    <h3 class="lib-title">مكتبة القطع (اضغط للاختيار)</h3>
    <div id="pe-lib" class="pe-lib" dir="ltr"></div>
    <footer class="sheet-foot">
      <button id="pe-save" class="act success big"><i class="fa-solid fa-check"></i> حفظ القطعة</button>
    </footer>
  </section>

  <!-- Board cell editor -->
  <section id="sheet-cell" class="sheet small hidden" dir="rtl">
    <header class="sheet-head"><h2>تعديل الخانة <span id="ce-pos"></span></h2><button class="close" data-close><i class="fa-solid fa-xmark"></i></button></header>
    <p class="sheet-sub">خانة مرقمة (مكافأة) — الحد الأقصى 3 خانات. الخانة تبقى فاضية وقانوني تحط قطعة فوقها.</p>
    <div class="bonus-picks" id="ce-bonus">
      <button data-bonus="50">50</button><button data-bonus="150">150</button><button data-bonus="300">300</button>
      <button data-bonus="500">500</button><button data-bonus="1000">1K</button><button data-bonus="2000">2K</button>
    </div>
    <div class="cell-actions">
      <button data-cell="1"><span class="mini-block blue"></span> مكعب أزرق</button>
      <button data-cell="2"><span class="mini-block orange"></span> مكعب أصفر</button>
      <button data-cell="0"><i class="fa-solid fa-eraser"></i> تفريغ الخانة</button>
    </div>
  </section>

  <!-- Settings -->
  <section id="sheet-settings" class="sheet hidden" dir="rtl">
    <header class="sheet-head"><h2>الإعدادات</h2><button class="close" data-close><i class="fa-solid fa-xmark"></i></button></header>
    <div class="settings">
      <div class="set-row"><label>قوة الذكاء الاصطناعي</label>
        <div class="seg" data-set="aiLevel"><button data-v="1">سريع</button><button data-v="2">قوي</button><button data-v="3">أقصى</button><button data-v="4" title="بحث شامل بلا قص + أعمق نظرة أمامية — بطيء جدًا">ULTRA</button></div></div>
      <div class="set-row"><label>ملء اللوحة في النهاية (كل مكعب باقي = 1000)</label>
        <div class="seg" data-set="fillLevels"><button data-v="2">آخر لفلين</button><button data-v="3">آخر 3</button><button data-v="4">آخر 4</button><button data-v="5">آخر 5</button></div></div>
      <div class="set-row"><label>أسلوب «اللوحة الفاضية» (امسح كتير قبل الملء)</label>
        <div class="seg" data-set="clean"><button data-v="0">إيقاف</button><button data-v="1">تشغيل</button></div></div>
      <div class="set-row"><label>سرعة الأنيميشن</label>
        <div class="seg" data-set="speed"><button data-v="1">بطيء</button><button data-v="2">عادي</button><button data-v="3">سريع</button></div></div>
      <div class="set-row"><label>مكافأة الرقم تُحسب</label>
        <div class="seg" data-set="bonusMode"><button data-v="clear">عند مسح الصف</button><button data-v="cover">عند التغطية</button></div></div>
      <div class="set-row"><label>نمو الأرقام (50→150→300…)</label>
        <div class="seg" data-set="growth"><button data-v="0">إيقاف</button><button data-v="1">كل لفل</button><button data-v="2">كل لفلين</button></div></div>
      <div class="set-row"><label>تنفيذ تلقائي بعد إدخال 3 قطع</label>
        <div class="seg" data-set="autoPlay"><button data-v="0">لا</button><button data-v="1">نعم</button></div></div>
      <div class="set-row"><label>الصوت</label>
        <div class="seg" data-set="sound"><button data-v="0">إيقاف</button><button data-v="1">تشغيل</button></div></div>
      <div class="set-row"><label>الاهتزاز</label>
        <div class="seg" data-set="haptics"><button data-v="0">إيقاف</button><button data-v="1">تشغيل</button></div></div>
    </div>
    <footer class="sheet-foot"><button class="act ghost big" id="set-reset-stats"><i class="fa-solid fa-trash"></i> تصفير الإحصائيات</button></footer>
  </section>

  <!-- Menu -->
  <section id="sheet-menu" class="sheet small hidden" dir="rtl">
    <header class="sheet-head"><h2>THNDR AI Block</h2><button class="close" data-close><i class="fa-solid fa-xmark"></i></button></header>
    <div class="menu-list">
      <button id="menu-new"><i class="fa-solid fa-rotate"></i> لعبة جديدة</button>
      <button id="menu-clear-board"><i class="fa-solid fa-broom"></i> تفريغ اللوحة فقط</button>
      <button id="menu-help"><i class="fa-solid fa-circle-question"></i> طريقة اللعب</button>
      <button id="menu-install"><i class="fa-solid fa-download"></i> تثبيت على الهاتف</button>
    </div>
  </section>

  <!-- Help -->
  <section id="sheet-help" class="sheet hidden" dir="rtl">
    <header class="sheet-head"><h2>طريقة اللعب</h2><button class="close" data-close><i class="fa-solid fa-xmark"></i></button></header>
    <div class="help">
      <p><b>1.</b> اضغط على خانة من الثلاث خانات تحت وارسم القطعة (أزرق/أصفر) أو اخترها من المكتبة.</p>
      <p><b>2.</b> اضغط على أي خانة فاضية في اللوحة لتحط رقم مكافأة (50 – 2K) — بحد أقصى 3 خانات — أو مكعب أزرق/أصفر لتطابق لوحتك الحقيقية.</p>
      <p><b>3.</b> بعد إدخال 3 قطع اضغط <b>خطة الذكاء</b> ليظهر مكان كل قطعة بالترتيب (1، 2، 3) ثم <b>تنفيذ</b>.</p>
      <p><b>القواعد:</b> يتمسح أي صف كامل، أو عمود كامل، أو <b>مربع 3×3</b> كامل (زي اللعبة الأصلية).</p>
      <p><b>4.</b> كل لفل = 3 قطع. اللفل 25 هو الأخير وبعده الفوز 🏆. اللعبة تنتهي لو مافيش مكان لأي قطعة.</p>
      <p><b>النقاط:</b> +1 لكل مكعب، مسح صف/عمود/مربع 3×3 = 20 × (عدد المسحات)² × المضاعف، الستريك يزيد النقاط، الخانة المرقمة تُضاف × المضاعف، والمكعب الأصفر يزوّد المضاعف عند مسحه.</p>
      <p><b>الذكاء الاصطناعي</b> يجرّب كل ترتيبات القطع الثلاث وكل الأماكن باستخدام Beam Search مع تقييم للفراغات والثقوب والصفوف القريبة من الامتلاء وقابلية وضع القطع مستقبلاً.</p>
    </div>
  </section>

  <!-- Level transition -->
  <div id="level-fx" class="level-fx hidden" dir="ltr">
    <div class="balls"><span></span><span></span><span></span></div>
    <div class="level-fx-text">LEVEL <span id="level-fx-num">2</span></div>
  </div>

  <!-- End screens -->
  <section id="sheet-end" class="sheet center hidden" dir="rtl">
    <div class="end-icon" id="end-icon">🏆</div>
    <h2 id="end-title">فوز!</h2>
    <p id="end-sub">وصلت للفل 25</p>
    <div class="end-stats"><div><small>النقاط</small><b id="end-score">0</b></div><div><small>اللفل</small><b id="end-level">1</b></div><div><small>المضاعف</small><b id="end-mult">1X</b></div></div>
    <div class="end-actions">
      <button id="end-undo" class="act ghost big"><i class="fa-solid fa-rotate-left"></i> تراجع خطوة</button>
      <button id="end-new" class="act success big"><i class="fa-solid fa-rotate"></i> لعبة جديدة</button>
    </div>
  </section>

  <div id="toast" class="toast hidden"></div>

  <script src="/static/engine.js?v=3"></script>
  <script src="/static/ai.js?v=3"></script>
  <script src="/static/app.js?v=3"></script>
</body>
</html>`)
})

export default app

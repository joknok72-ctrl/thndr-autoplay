package com.thndr.autoplay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.thndr.autoplay.engine.AI
import com.thndr.autoplay.engine.Engine
import com.thndr.autoplay.engine.N
import com.thndr.autoplay.vision.Screen
import com.thndr.autoplay.vision.ScreenParser
import kotlin.math.roundToInt

/**
 * Foreground service: captures the screen (MediaProjection), parses the THNDR board,
 * plans with the AI and drags pieces via GestureService. Fully autonomous loop.
 */
class BotService : Service() {
    companion object {
        const val TAG = "ThndrBot"
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"; const val ACTION_TOGGLE = "toggle"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"
        @Volatile var running = false
        @Volatile var status: String = "متوقف"
        @Volatile var lastBoard: String = ""
        @Volatile var movesDone = 0
        var listener: ((String) -> Unit)? = null
        fun report(s: String) { status = s; Log.i(TAG, s); listener?.invoke(s) }
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var overlay: OverlayController? = null
    private var guide: GuideOverlay? = null
    private var W = 0; private var H = 0
    private var stopFlag = false
    @Volatile private var manualNext = false
    @Volatile private var forceReplan = false
    @Volatile private var newGameReq = false
    @Volatile private var shareReq = false
    @Volatile private var pendingBitmap: android.graphics.Bitmap? = null
    @Volatile private var confirmedPieces: List<com.thndr.autoplay.engine.Piece?>? = null
    @Volatile private var pendingScreen: Screen? = null
    // HUD memory: level & multiplier never go DOWN inside a game (only a new game resets them).
    // If the OCR misses a frame (returns 1) we keep the last good value — otherwise the planner would think 70 moves remain
    // and never switch to the end-game fill strategy.
    private var memLevel = 1; private var memMult = 1
    /** Points banked so far this game (sum of executed plan totals — matches the real score within ~1% in the logs). Lost on death. */
    private var bankedScore = 0
    /** Strategy knobs from settings → planner fields. */
    private fun applyStrategy() {
        AI.END_FADE = prefs.getInt("fillMoves", 9).coerceIn(3, 15)              // fill the board in the last N moves (3 per level)
        AI.W_CLEAN = 0.0                                                          // clean-board style removed (scored lower in tests)
        tuneThreads()
    }
    /** Size the planner's thread pool from the RAM that is free RIGHT NOW (the game + system keep the rest). All cores when RAM is free. */
    var lastThreads = AI.CORES
    private fun tuneThreads() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            // availMem already excludes what the game/other apps hold; keep the system's low-memory threshold as a safety margin
            lastThreads = AI.tuneThreads(mi.availMem - mi.threshold, mi.totalMem)
        } catch (_: Throwable) { lastThreads = AI.tuneThreads(-1, -1) }
    }
    private fun fixHud(scr: Screen): Screen {
        val cubes = scr.board.count { it != 0 }
        // A new game is detected AUTOMATICALLY: the HUD reads "LEVEL 1/25" (or nothing) + "1X" with a nearly empty board.
        val newGame = scr.level <= 1 && scr.mult <= 1 && cubes <= 6
        if (newGame) {
            if (memLevel > 1 || memMult > 1) { GameLog.newGame(this); guide?.flash("🆕 اتعرفت على جولة جديدة (لفل 1 · 1X)", 1500) }
            memLevel = 1; memMult = 1; bankedScore = 0; return scr
        }
        // OCR result is trusted when plausible (never goes backwards by more than 1); otherwise keep the memory.
        val lvl = if (scr.level in 2..25 && scr.level >= memLevel - 1) scr.level else memLevel
        val mul = if (scr.mult in 2..99 && scr.mult >= memMult - 1) scr.mult else memMult
        memLevel = maxOf(memLevel, lvl); memMult = maxOf(memMult, mul)
        return if (lvl == scr.level && mul == scr.mult) scr else scr.copy(mult = mul, level = lvl)
    }
    private var editor: PieceEditorOverlay? = null
    private val prefs by lazy { getSharedPreferences("bot", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startFg()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) setupProjection(code, data)
                overlay = OverlayController(this, { toggle() }, { manualNext = true }, { forceReplan = true }, { newGameReq = true }, { shareReq = true }).also { it.show() }
                guide = GuideOverlay(this).also { it.show() }
                editor = PieceEditorOverlay(this, { pieces -> editor?.hide(); confirmedPieces = pieces }, { editor?.hide(); pendingScreen = null; guide?.setMessage("تم الإلغاء — اضغط «خطة» تاني") })
                report("جاهز — اضغط ▶ فوق اللعبة")
            }
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> { stopLoop(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val chId = "bot"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(chId) == null) nm.createNotificationChannel(NotificationChannel(chId, "THNDR Bot", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, chId).setSmallIcon(R.drawable.ic_bolt).setContentTitle("THNDR AutoPlay")
            .setContentText("البوت شغال في الخلفية").addAction(Notification.Action.Builder(null, "إيقاف", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopLoop(); report("تم إيقاف تسجيل الشاشة") } }, null)
        val wm = getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        W = dm.widthPixels; H = dm.heightPixels
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vdisplay = projection?.createVirtualDisplay("thndr", W, H, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    fun capture(): Bitmap? {
        val r = reader ?: return null
        var img = r.acquireLatestImage()
        var tries = 0
        while (img == null && tries < 20) { Thread.sleep(50); img = r.acquireLatestImage(); tries++ }
        img ?: return null
        try {
            val plane = img.planes[0]; val stride = plane.rowStride; val ps = plane.pixelStride
            val bmp = Bitmap.createBitmap(stride / ps, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            return if (bmp.width != W) Bitmap.createBitmap(bmp, 0, 0, W, H) else bmp
        } finally { img.close() }
    }

    private fun toggle() { if (running) stopLoop() else startLoop() }
    private fun isAuto() = prefs.getInt("mode", 0) == 1   // 0 = guide (you drag, it shows where), 1 = auto (bot drags)

    private fun startLoop() {
        if (projection == null) { report("لازم تسمح بتسجيل الشاشة أولاً"); return }
        if (isAuto() && !GestureService.isRunning) { report("فعّل خدمة الوصول (Accessibility) للتطبيق"); return }
        stopFlag = false; running = true; overlay?.setRunning(true)
        worker = HandlerThread("bot").also { it.start(); handler = Handler(it.looper) }
        handler?.post { loop() }
    }

    private fun stopLoop() {
        stopFlag = true; running = false; overlay?.setRunning(false)
        worker?.quitSafely(); worker = null
        guide?.clear()
        report("متوقف")
    }

    // ---------- main loop ----------
    private var idleCount = 0
    private fun loop() { if (isAuto()) autoLoop() else guideLoop() }

    /**
     * Guide mode — fully manual pacing, NO automatic re-planning:
     *  • "خطة" (plan button): read the screen ONCE, compute the best order for the 3 pieces, draw ①②③ and FREEZE.
     *  • The user places the pieces at their own pace; the drawing never changes on its own.
     *  • "التالي": just moves the green highlight to the next step (optional).
     *  • When the next 3 pieces appear the user presses "خطة" again.
     */
    private fun guideLoop() {
        var steps: List<GuideOverlay.Step> = emptyList()
        var cur = 0
        var frozen: Screen? = null           // screen snapshot the plan was built on (board rect + tray rects)
        overlay?.setNextVisible(true)
        guide?.setMessage("اضغط «خطة» لما القطع الثلاث تظهر")
        report("جاهز — اضغط «خطة»")
        while (!stopFlag) {
            try {
                if (newGameReq) {
                    newGameReq = false
                    memLevel = 1; memMult = 1; GameLog.newGame(this); steps = emptyList(); frozen = null
                    vibrate(longArrayOf(0, 30, 40, 30, 40, 30))
                    guide?.flash("🆕 جولة جديدة من الصفر — اللفل 1 والمضاعف 1X", 1800); report("جولة جديدة")
                }
                if (shareReq) {
                    shareReq = false
                    if (GameLog.share(this)) report("بشارك سجل الجولة (${GameLog.count()} خطة)…") else guide?.flash("مافيش سجل لسه — اعمل خطة الأول", 1500)
                }
                if (forceReplan) {
                    forceReplan = false
                    // hide ALL overlay drawings first so the screenshot contains only the game
                    guide?.clear(); overlay?.setHiddenForCapture(true)
                    Thread.sleep(260)
                    // read TWO frames ~150 ms apart and keep the one whose tray reading agrees (kills mid-animation reads)
                    var bmp = capture(); Thread.sleep(60); bmp = capture() ?: bmp   // take the freshest frame
                    var scr = try { bmp?.let { ScreenParser.parse(it) } } catch (e: ScreenParser.ParseException) { null }
                    Thread.sleep(150)
                    val bmp2 = capture()
                    val scr2 = try { bmp2?.let { ScreenParser.parse(it) } } catch (e: ScreenParser.ParseException) { null }
                    if (scr2 != null && (scr == null || scr2.piecesFound > scr.piecesFound || scr2.tray.map { it?.piece?.toString() } != scr.tray.map { it?.piece?.toString() })) scr = scr2
                    if (scr != null) scr = fixHud(scr)
                    pendingBitmap = bmp2 ?: bmp
                    overlay?.setHiddenForCapture(false)
                    guide?.setMessage("بقرأ الشاشة وبفكر…"); report("بفكر…")
                    if (scr == null) { guide?.setMessage("مش شايف اللوحة — افتح اللعبة واضغط «خطة» تاني"); report("مش شايف اللوحة"); Thread.sleep(300); continue }
                    lastBoard = scr.boardString()
                    if (scr.piecesFound == 0) { runCatching { GameLog.record(this, scr, scr.tray.map { it?.piece }, com.thndr.autoplay.engine.Plan(emptyList(), 0, false), pendingBitmap, "NO_PIECES_READ") }; pendingBitmap = null; guide?.setMessage("مافيش قطع في الصينية — استنى لما تظهر واضغط «خطة»"); report("مافيش قطع"); Thread.sleep(300); continue }
                    // Show what we READ and let the user confirm/fix the shapes ("he draws the cubes himself")
                    pendingScreen = scr
                    if (prefs.getBoolean("confirmPieces", false)) {
                        guide?.setMessage("راجع القطع الثلاث وعدّلها لو فيه غلط، ثم ✓"); report("راجع القطع ثم ✓")
                        val xs = FloatArray(3) { i -> scr.tray.getOrNull(i)?.cx ?: ((i + 0.5f) * scr.width / 3f) }
                        val trayTop = scr.tray.filterNotNull().minOfOrNull { it.y0.toFloat() } ?: (scr.by1 + scr.pitch * 3.2f)
                        editor?.setAnchors(xs, trayTop)
                        editor?.show(scr.tray.map { it?.piece })
                    } else {
                        confirmedPieces = scr.tray.map { it?.piece }   // trust the reading, go straight to the plan
                    }
                }
                val confirmed = confirmedPieces
                val ps = pendingScreen
                if (confirmed != null && ps != null) {
                    confirmedPieces = null; pendingScreen = null
                    // tray rect per slot: use the read rect if present, else a default slot box under the board
                    val slotW = ps.width / 3f
                    applyStrategy()
                    val deep = prefs.getBoolean("deepEnd", true)
                    if (deep && ps.level >= 23) guide?.setMessage("بحث أعمق لآخر اللفلات (${ps.level}/25) — زي الموقع بالضبط، استنى شوية…")
                    val lvlPref = prefs.getInt("level", 3).coerceIn(1, 4)
                    if (lvlPref >= 4) guide?.setMessage("ULTRA — بحث شامل (1–3 دقايق للجولة)… ${lastThreads}/${AI.CORES} كور")
                    // ULTRA can exhaust memory on small phones → fall back to level 3 for this round instead of dying
                    val plan = try { AI.plan(ps.board, ps.bonus, confirmed, ps.mult, ps.level, lvlPref, deep, true, bankedScore) }
                               catch (_: OutOfMemoryError) { System.gc(); guide?.flash("الرام مش كافية لـULTRA — الجولة دي بمستوى الأقصى", 2500); AI.plan(ps.board, ps.bonus, confirmed, ps.mult, ps.level, 3, deep, true, bankedScore) }
                    if (plan.gameOver || plan.moves.isEmpty()) {
                        runCatching { GameLog.record(this, ps, confirmed, plan, pendingBitmap, "GAME_OVER: no placement for any order") }; pendingBitmap = null
                        guide?.setMessage("مافيش مكان لأي قطعة — Game Over"); report("Game Over"); Thread.sleep(300); continue
                    }
                    steps = plan.moves.map { m ->
                        val piece = confirmed[m.slot]!!
                        val tp = ps.tray.getOrNull(m.slot)
                        val rect = if (tp != null) RectF(tp.x0.toFloat(), tp.y0.toFloat(), tp.x1.toFloat(), tp.y1.toFloat())
                                   else RectF(m.slot * slotW + slotW * 0.2f, ps.by1 + ps.pitch * 3.2f, (m.slot + 1) * slotW - slotW * 0.2f, ps.by1 + ps.pitch * 5.2f)
                        GuideOverlay.Step(piece, rect, m.slot, m.r, m.c, m.points)
                    }
                    cur = 0; frozen = ps; bankedScore += plan.total
                    vibrate(longArrayOf(0, 30, 40, 30))
                    val phase = if (75 - (ps.level - 1) * 3 <= AI.END_FADE + 2) " · مرحلة الملء" else ""
                    guide?.flash("الخطة جاهزة (${ps.mult}X · لفل ${ps.level}$phase · ${lastThreads}/${AI.CORES} كور) — +${plan.total} نقطة")
                    runCatching { GameLog.record(this, ps, confirmed, plan, pendingBitmap) }; pendingBitmap = null
                }
                if (manualNext) {
                    manualNext = false
                    if (steps.isNotEmpty()) {
                        cur++; movesDone++
                        vibrate(longArrayOf(0, 40))
                        if (cur >= steps.size) { steps = emptyList(); guide?.setMessage("✅ خلصت الجولة — لما القطع الجديدة تظهر اضغط «خطة»"); report("خلصت الجولة") }
                    }
                }
                val fz = frozen
                if (steps.isNotEmpty() && fz != null && cur < steps.size) {
                    val st = steps[cur]
                    val name = when (st.trayIndex) { 0 -> "اليسرى"; 1 -> "الوسطى"; else -> "اليمنى" }
                    val text = "${cur + 1}  ←  $name"
                    val sub = "+${st.points}"
                    guide?.setPlan(GuideOverlay.PlanView(fz.bx0.toFloat(), fz.by0.toFloat(), fz.pitch, steps, cur, text, sub))
                    report("الخطوة ${cur + 1}/${steps.size}: قطعة ${st.trayIndex + 1} → صف ${st.r + 1} عمود ${st.c + 1}")
                }
                Thread.sleep(120)
            } catch (e: Throwable) {
                Log.e(TAG, "guide error", e); report("خطأ: ${e.message}"); Thread.sleep(500)
            }
        }
        overlay?.setNextVisible(false)
        guide?.clear()
    }

    /** True when every target cell of the piece became filled (or the line/box containing it got cleared) compared with the base board. */
    private fun landedAt(base: IntArray, cur: IntArray, p: com.thndr.autoplay.engine.Piece, r: Int, c: Int): Boolean {
        val expect = Engine.place(base, IntArray(N * N), p, r, c)
        // if the placement causes clears, cells may be empty now: compare against the expected post-clear board instead
        var match = 0; var total = 0
        for (cell in p.cells) {
            val i = (r + cell.r) * N + (c + cell.c); total++
            val want = expect.board[i] != 0
            if ((cur[i] != 0) == want) match++
        }
        if (match < total) return false
        // also make sure the rest of the board didn't get NEW cubes elsewhere (piece placed somewhere else)
        var extra = 0
        for (i in 0 until N * N) if (cur[i] != 0 && expect.board[i] == 0) extra++
        return extra <= 1
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VibratorManager::class.java)).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {}
    }

    /** Same shape (and colors) as planned, in the current tray. */
    private fun matchTrayPiece(scr: Screen, planned: com.thndr.autoplay.engine.Piece): com.thndr.autoplay.vision.TrayPiece? {
        val key = planned.toString()
        return scr.tray.firstOrNull { it != null && it.piece.toString() == key }
            ?: scr.tray.firstOrNull { it != null && it.piece.cells.map { c -> c.r to c.c } == planned.cells.map { c -> c.r to c.c } }
    }

    /** Boards are compatible if current has no filled cell where expected is empty (clears may remove cells). */
    private fun boardsCompatible(expect: IntArray, cur: IntArray): Boolean {
        var bad = 0
        for (i in expect.indices) if (cur[i] != 0 && expect[i] == 0) bad++
        return bad <= 1
    }

    /** Human-friendly position: "أعلى اليسار، صف 2 عمود 3" using the piece's top-left cube. */
    private fun describe(r: Int, c: Int, p: com.thndr.autoplay.engine.Piece): String {
        val cr = r + (p.h - 1) / 2f; val cc = c + (p.w - 1) / 2f
        val v = when { cr < 3 -> "أعلى"; cr < 6 -> "وسط"; else -> "أسفل" }
        val hz = when { cc < 3 -> "اليسار"; cc < 6 -> "الوسط"; else -> "اليمين" }
        val zone = if (v == "وسط" && hz == "الوسط") "قلب اللوحة" else "$v $hz"
        return "$zone (صف ${r + 1}، عمود ${c + 1})"
    }

    /** Auto mode: the bot drags the pieces itself via the accessibility service. */
    private fun autoLoop() {
        var lastSig = ""
        var sameCount = 0
        while (!stopFlag) {
            try {
                val bmp = capture() ?: run { Thread.sleep(200); null } ?: continue
                val scr = try { fixHud(ScreenParser.parse(bmp)) } catch (e: ScreenParser.ParseException) {
                    report("مش شايف اللوحة — افتح اللعبة (${e.message})"); Thread.sleep(700); continue
                }
                lastBoard = scr.boardString()
                if (scr.piecesFound == 0) {
                    idleCount++
                    report(if (idleCount > 6) "مافيش قطع — انتهت الجولة/اللعبة؟" else "بانتظار القطع…")
                    Thread.sleep(600); continue
                }
                idleCount = 0
                val sig = lastBoard + scr.tray.joinToString { it?.piece?.toString() ?: "-" }
                if (sig == lastSig) { sameCount++; if (sameCount > 3) { report("الشاشة ما اتغيرتش — بعيد المحاولة"); prefs.edit().putBoolean("calibrated", false).apply() } } else sameCount = 0
                lastSig = sig

                val pieces = scr.tray.map { it?.piece }
                report("بفكر… (${scr.piecesFound} قطع)"); applyStrategy()
                val plan = AI.plan(scr.board, scr.bonus, pieces, scr.mult, scr.level, prefs.getInt("level", 3).coerceIn(1, 4), prefs.getBoolean("deepEnd", true), true, bankedScore)
                if (plan.gameOver || plan.moves.isEmpty()) { report("مافيش حركة ممكنة — Game Over"); Thread.sleep(1500); continue }

                val mv = plan.moves[0]; val tp = scr.tray[mv.slot]!!
                // show the same hint while dragging so the user sees what the bot intends
                guide?.setPlan(GuideOverlay.PlanView(scr.bx0.toFloat(), scr.by0.toFloat(), scr.pitch,
                    listOf(GuideOverlay.Step(tp.piece, RectF(tp.x0.toFloat(), tp.y0.toFloat(), tp.x1.toFloat(), tp.y1.toFloat()), mv.slot, mv.r, mv.c, mv.points)), 0, "البوت يسحب القطعة ${mv.slot + 1}", null))
                val ok = performMove(scr, tp, mv.r, mv.c)
                if (!ok) { report("الإيماءة فشلت"); Thread.sleep(500); continue }
                movesDone++
                report("حركة #$movesDone: قطعة ${mv.slot + 1} → (${mv.r + 1},${mv.c + 1}) +${mv.points}")
                Thread.sleep(prefs.getInt("delay", 650).toLong())
                verifyAndCalibrate(scr, tp, mv.r, mv.c)
            } catch (e: Throwable) {
                Log.e(TAG, "loop error", e); report("خطأ: ${e.message}"); Thread.sleep(800)
            }
        }
        guide?.clear()
    }

    /**
     * Drag so that the piece's top-left cube lands on board cell (r,c).
     * In THNDR the piece scales up to board size while dragging and the piece is held around its center,
     * so the drop point = board position of the piece's center + finger offset (auto-calibrated).
     */
    private fun performMove(scr: Screen, tp: com.thndr.autoplay.vision.TrayPiece, r: Int, c: Int): Boolean {
        val g = GestureService.instance ?: return false
        val p = tp.piece
        // board coordinates of the piece's center once placed with top-left at (r,c)
        val cx = scr.bx0 + (c + p.w / 2f) * scr.pitch
        val cy = scr.by0 + (r + p.h / 2f) * scr.pitch
        val offX = prefs.getFloat("offX", 0f); val offY = prefs.getFloat("offY", -scr.pitch * 0.9f) // finger sits slightly below the piece in most block games
        // grab point: center of the tray piece
        val gx = tp.cx; val gy = tp.cy
        return g.drag(gx, gy, cx + offX, cy + offY, holdMs = 160, moveMs = prefs.getInt("moveMs", 420).toLong(), settleMs = 260)
    }

    /** After the drop, check where the piece actually landed; if shifted by whole cells, adjust offsets. */
    private fun verifyAndCalibrate(before: Screen, tp: com.thndr.autoplay.vision.TrayPiece, r: Int, c: Int) {
        val bmp = capture() ?: return
        val after = try { ScreenParser.parse(bmp) } catch (e: Exception) { return }
        val p = tp.piece
        // expected: cells filled at (r+cr, c+cc). Search small shifts for best match (ignoring clears).
        var bestDr = 0; var bestDc = 0; var bestHit = -1
        for (dr in -2..2) for (dc in -2..2) {
            var hit = 0; var okAll = true
            for (cell in p.cells) {
                val rr = r + cell.r + dr; val cc = c + cell.c + dc
                if (rr !in 0 until N || cc !in 0 until N) { okAll = false; break }
                val i = rr * N + cc
                if (after.board[i] != 0 && before.board[i] == 0) hit++
            }
            if (okAll && hit > bestHit) { bestHit = hit; bestDr = dr; bestDc = dc }
        }
        val placedSomewhere = after.tray.count { it != null } < before.tray.count { it != null } || bestHit == p.size
        if (!placedSomewhere) { report("القطعة ما نزلتش — بعدل المعايرة"); nudge(0f, -before.pitch * 0.25f); return }
        if (bestHit >= p.size - 1 && (bestDr != 0 || bestDc != 0)) {
            // landed shifted by (dr,dc): compensate
            nudge(-bestDc * before.pitch, -bestDr * before.pitch)
            report("معايرة: تعديل الإزاحة (${-bestDc},${-bestDr}) خانة")
        } else if (bestDr == 0 && bestDc == 0 && bestHit >= p.size - 1) prefs.edit().putBoolean("calibrated", true).apply()
    }
    private fun nudge(dx: Float, dy: Float) {
        prefs.edit().putFloat("offX", prefs.getFloat("offX", 0f) + dx).putFloat("offY", prefs.getFloat("offY", 0f) + dy).apply()
    }

    override fun onDestroy() {
        stopLoop(); overlay?.hide(); guide?.hide(); editor?.hide(); vdisplay?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}

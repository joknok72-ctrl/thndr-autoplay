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
import kotlin.math.abs
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
    @Volatile private var confirmedPieces: List<com.thndr.autoplay.engine.Piece?>? = null
    @Volatile private var pendingScreen: Screen? = null
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
                overlay = OverlayController(this, { toggle() }, { manualNext = true }, { forceReplan = true }).also { it.show() }
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
    private fun isAuto() = prefs.getInt("mode", 0) == 1   // 0 = guide (you drag, it shows where), 1 = auto (bot drags), 2 = relay (Device Relay drags)
    private fun isRelay() = prefs.getInt("mode", 0) == 2
    private fun relay(): RelayClient? {
        val tok = prefs.getString("relayToken", "")!!.trim(); val dev = prefs.getString("relayDevice", "")!!.trim()
        if (tok.isBlank() || dev.isBlank()) return null
        return RelayClient(prefs.getString("relayServer", RelayClient.DEFAULT_SERVER)!!, tok, dev)
    }

    private fun startLoop() {
        if (projection == null) { report("لازم تسمح بتسجيل الشاشة أولاً"); return }
        if (isAuto() && !GestureService.isRunning) { report("فعّل خدمة الوصول (Accessibility) للتطبيق"); return }
        if (isRelay() && relay() == null) { report("أدخل توكن Device Relay ومعرّف الجهاز في الإعدادات"); return }
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
    private fun loop() { when { isRelay() -> relayLoop(); isAuto() -> autoLoop(); else -> guideLoop() } }

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
                if (forceReplan) {
                    forceReplan = false
                    // hide ALL overlay drawings first so the screenshot contains only the game
                    guide?.clear(); overlay?.setHiddenForCapture(true)
                    Thread.sleep(260)
                    var bmp = capture(); Thread.sleep(60); bmp = capture() ?: bmp   // take the freshest frame
                    overlay?.setHiddenForCapture(false)
                    guide?.setMessage("بقرأ الشاشة وبفكر…"); report("بفكر…")
                    val scr = try { bmp?.let { ScreenParser.parse(it) } } catch (e: ScreenParser.ParseException) { null }
                    if (scr == null) { guide?.setMessage("مش شايف اللوحة — افتح اللعبة واضغط «خطة» تاني"); report("مش شايف اللوحة"); Thread.sleep(300); continue }
                    lastBoard = scr.boardString()
                    if (scr.piecesFound == 0) { guide?.setMessage("مافيش قطع في الصينية — استنى لما تظهر واضغط «خطة»"); report("مافيش قطع"); Thread.sleep(300); continue }
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
                    val plan = AI.plan(ps.board, ps.bonus, confirmed, 0, 1, prefs.getInt("level", 4))
                    if (plan.gameOver || plan.moves.isEmpty()) { guide?.setMessage("مافيش مكان لأي قطعة — Game Over"); report("Game Over"); Thread.sleep(300); continue }
                    steps = plan.moves.map { m ->
                        val piece = confirmed[m.slot]!!
                        val tp = ps.tray.getOrNull(m.slot)
                        val rect = if (tp != null) RectF(tp.x0.toFloat(), tp.y0.toFloat(), tp.x1.toFloat(), tp.y1.toFloat())
                                   else RectF(m.slot * slotW + slotW * 0.2f, ps.by1 + ps.pitch * 3.2f, (m.slot + 1) * slotW - slotW * 0.2f, ps.by1 + ps.pitch * 5.2f)
                        GuideOverlay.Step(piece, rect, m.slot, m.r, m.c, m.points)
                    }
                    cur = 0; frozen = ps
                    vibrate(longArrayOf(0, 30, 40, 30))
                    guide?.flash("الخطة جاهزة: ${steps.size} قطع — حطهم بالترتيب")
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

    // ---------- Device Relay mode ----------
    /**
     * Fully autonomous play through the Device Relay app (its AccessibilityService performs the drags,
     * which is proven to work on the user's phone, while this app keeps doing the vision + AI):
     *  capture (MediaProjection) → parse board/tray → [optional: user approves the read pieces] →
     *  AI plan for the round → for each piece: drag via relay → capture → verify landing → calibrate offsets.
     */
    private fun relayLoop() {
        val rc = relay() ?: run { report("أدخل توكن Device Relay ومعرّف الجهاز"); running = false; overlay?.setRunning(false); return }
        guide?.setMessage("بتأكد من اتصال Device Relay…"); report("بتأكد من الاتصال…")
        val (on, detail) = rc.online()
        if (!on) { report(detail); guide?.setMessage("❌ $detail"); Thread.sleep(2500); running = false; overlay?.setRunning(false); guide?.clear(); return }
        report(detail); guide?.flash("Device Relay $detail — البوت هيلعب لوحده")
        rc.releaseAll()   // no finger left down from a previous run
        loadCal()
        var lastSig = ""; var stuck = 0; var failures = 0
        while (!stopFlag) {
            try {
                val scr = cleanCapture() ?: run { report("مش شايف اللوحة — افتح اللعبة"); guide?.setMessage("مش شايف اللوحة — افتح لعبة THNDR"); Thread.sleep(900); null } ?: continue
                lastBoard = scr.boardString()
                if (scr.piecesFound == 0) {
                    idleCount++
                    val m = if (idleCount > 8) "مافيش قطع — انتهت الجولة/اللعبة؟ (بستنى)" else "بانتظار القطع…"
                    report(m); guide?.setMessage(m); Thread.sleep(700); continue
                }
                idleCount = 0
                val sig = lastBoard + scr.tray.joinToString { it?.piece?.toString() ?: "-" }
                if (sig == lastSig) { stuck++; if (stuck >= 3) { report("الشاشة ما بتتغيرش — بعيد قياس الهندسة"); cal = null; stuck = 0 } } else stuck = 0
                lastSig = sig

                // ---- optional approval of the read pieces ("I only approve the drawing") ----
                var pieces: List<com.thndr.autoplay.engine.Piece?> = scr.tray.map { it?.piece }
                if (prefs.getBoolean("confirmPieces", false)) {
                    confirmedPieces = null; pendingScreen = scr
                    val xs = FloatArray(3) { i -> scr.tray.getOrNull(i)?.cx ?: ((i + 0.5f) * scr.width / 3f) }
                    val trayTop = scr.tray.filterNotNull().minOfOrNull { it.y0.toFloat() } ?: (scr.by1 + scr.pitch * 3.2f)
                    editor?.setAnchors(xs, trayTop); editor?.show(pieces)
                    guide?.setMessage("راجع القطع الثلاث ثم ✓ — البوت هيسحبهم لوحده"); report("بانتظار موافقتك على القطع")
                    while (!stopFlag && confirmedPieces == null && pendingScreen != null) Thread.sleep(80)
                    if (stopFlag) break
                    val ok = confirmedPieces
                    if (ok == null) { guide?.setMessage("تم الإلغاء — بقرأ الشاشة تاني"); Thread.sleep(600); continue }
                    pieces = ok; confirmedPieces = null; pendingScreen = null
                }

                // ---- plan the whole round ----
                report("بفكر… (${pieces.count { it != null }} قطع)"); guide?.setMessage("بفكر…")
                val plan = AI.plan(scr.board, scr.bonus, pieces, 0, 1, prefs.getInt("level", 4))
                if (plan.gameOver || plan.moves.isEmpty()) { report("مافيش حركة ممكنة — Game Over"); guide?.setMessage("مافيش مكان لأي قطعة — Game Over"); Thread.sleep(2000); continue }
                val slotW = scr.width / 3f
                val steps = plan.moves.map { m ->
                    val tp = scr.tray.getOrNull(m.slot)
                    val rect = if (tp != null) RectF(tp.x0.toFloat(), tp.y0.toFloat(), tp.x1.toFloat(), tp.y1.toFloat())
                               else RectF(m.slot * slotW + slotW * 0.2f, scr.by1 + scr.pitch * 3.2f, (m.slot + 1) * slotW - slotW * 0.2f, scr.by1 + scr.pitch * 5.2f)
                    GuideOverlay.Step(pieces[m.slot]!!, rect, m.slot, m.r, m.c, m.points)
                }

                // ---- execute move by move, verifying each landing ----
                var board = scr.board.copyOf(); var bonus = scr.bonus.copyOf(); var mult = 1
                var roundOk = true
                for ((i, m) in plan.moves.withIndex()) {
                    if (stopFlag) break
                    val piece = pieces[m.slot]!!
                    guide?.setPlan(GuideOverlay.PlanView(scr.bx0.toFloat(), scr.by0.toFloat(), scr.pitch, steps, i, "${i + 1}  ←  بتنزل في مكانها بالظبط", "+${m.points}"))
                    Thread.sleep(150)
                    // freshest tray position for this slot (pieces keep their slots, but re-read to be safe)
                    val tp = scr.tray.getOrNull(m.slot) ?: run { roundOk = false; null } ?: break
                    // baseline frame (board without any flying piece)
                    val base = captureClean() ?: run { roundOk = false; null } ?: break
                    // 1) geometry known for this board size? if not, measure it with a harmless pass (nothing is placed)
                    if (cal == null || abs(calPitch - scr.pitch) > 1.5f) {
                        guide?.setMessage("بقيس هندسة السحب مرة واحدة…"); report("بقيس الهندسة…")
                        val (cc, msg) = measureGeometry(rc, scr, base, tp, piece)
                        if (cc == null) { failures++; report(msg); guide?.setMessage("❌ $msg"); roundOk = false; Thread.sleep(600); break }
                        cal = cc; calPitch = scr.pitch; report(msg)
                        // the piece went back to its slot; re-read the screen so the tray box is fresh
                        val again = waitSettled() ?: run { roundOk = false; null } ?: break
                        if (!boardsCompatible(scr.board, again.board) || again.piecesFound != scr.piecesFound) { report("الشاشة اتغيرت بعد القياس — بعيد التخطيط"); roundOk = false; break }
                    }
                    // 2) one exact drag
                    val r = placeExact(rc, scr, tp, piece, m.r, m.c, cal!!)
                    if (!r.first) {
                        failures++
                        report("النقل فشل: ${r.second}"); guide?.setMessage("❌ ${r.second}")
                        if (failures >= 3) { val (o, d) = rc.online(); if (!o) { guide?.setMessage("❌ $d"); Thread.sleep(3000) } ; failures = 0 }
                        roundOk = false; Thread.sleep(500); break
                    }
                    failures = 0
                    // verify (wait until the game's animation settled instead of a fixed delay)
                    val expect = Engine.place(board, bonus, piece, m.r, m.c)
                    val after = waitSettled()
                    if (after == null) { roundOk = false; break }
                    if (landedAt(board, after.board, piece, m.r, m.c)) {
                        movesDone++
                        report("✅ #$movesDone: قطعة ${m.slot + 1} → (${m.r + 1},${m.c + 1}) +${m.points} — ${r.second}")
                        board = expect.board; bonus = expect.bonus; if (expect.orangeCleared > 0) mult++
                    } else {
                        // landed elsewhere? — correct the measured geometry by the exact cell shift and re-plan from the real screen
                        val shift = findShift(board, after.board, piece, m.r, m.c)
                        if (shift == null) { report("القطعة ما نزلتش — بعيد القياس"); cal = null }
                        else {
                            // landed shifted by whole cells → our geometry is off by exactly that; fix it (no re-measure needed)
                            val cc = cal!!; cal = Cal(cc.offX + shift.second * scr.pitch, cc.offY + shift.first * scr.pitch, cc.scale)
                            report("نزلت مزحزحة (${shift.first},${shift.second}) — صحّحت الهندسة")
                        }
                        roundOk = false; break
                    }
                }
                guide?.clear()
                if (roundOk) { vibrate(longArrayOf(0, 25)); Thread.sleep(350) }
            } catch (e: Throwable) {
                Log.e(TAG, "relay loop error", e); report("خطأ: ${e.message}"); Thread.sleep(900)
            }
        }
        rc.releaseAll()
        editor?.hide(); guide?.clear()
    }

    // ---------- "measure then lock": learn the finger→piece geometry BEFORE placing anything ----------
    /**
     * The game positions a dragged piece relative to the FINGER with a fixed offset and a fixed scale (the piece grows
     * to board size). We measure both with a harmless pass: lift the piece, hover it over the board (no drop — the stroke
     * returns to the tray before releasing), photograph it in the air, and compare its cube grid to the finger point.
     * Result: exact finger offset (px) + scale. Cached per board geometry (pitch) so it runs once per game/theme.
     */
    private class Cal(val offX: Float, val offY: Float, val scale: Float)
    private var cal: Cal? = null
        set(v) { field = v; prefs.edit().apply { if (v == null) remove("calOffX") else putFloat("calOffX", v.offX).putFloat("calOffY", v.offY).putFloat("calScale", v.scale) }.apply() }
    private var calPitch = 0f
        set(v) { field = v; prefs.edit().putFloat("calPitch", v).apply() }
    private fun loadCal() {
        if (prefs.contains("calOffX")) { cal = Cal(prefs.getFloat("calOffX", 0f), prefs.getFloat("calOffY", 0f), prefs.getFloat("calScale", 1f)); calPitch = prefs.getFloat("calPitch", 0f) }
    }

    /**
     * Builds ONE stroke with exact timing. Android replays a path at constant speed, so a "dwell" is a tight zig-zag
     * whose length equals speed × ms (the same trick Device Relay uses for joystick holds).
     */
    private class Stroke(private val speedPxPerMs: Float) {
        val pts = ArrayList<Pair<Float, Float>>(); var ms = 0f; private var x = 0f; private var y = 0f
        fun start(x0: Float, y0: Float) { x = x0; y = y0; pts.add(x to y) }
        fun moveTo(nx: Float, ny: Float) { val d = Math.hypot((nx - x).toDouble(), (ny - y).toDouble()).toFloat(); ms += d / speedPxPerMs; x = nx; y = ny; pts.add(x to y) }
        fun dwell(dur: Float) { val len = dur * speedPxPerMs; val n = maxOf(1, (len / 3f).roundToInt()); for (i in 0 until n) { pts.add(x + 1.5f to y); pts.add(x to y) }; ms += n * 3f / speedPxPerMs }
        val totalMs get() = ms.roundToInt().toLong().coerceAtLeast(60)
    }
    private val SPEED = 1.6f   // px per ms while moving (≈ a calm human drag)

    private fun measureGeometry(rc: RelayClient, scr: Screen, base: Bitmap, tp: com.thndr.autoplay.vision.TrayPiece, piece: com.thndr.autoplay.engine.Piece): Pair<Cal?, String> {
        val pitch = scr.pitch
        val hx = scr.bx0 + 4.5f * pitch; val hy = scr.by0 + 4.5f * pitch + pitch * 0.9f   // hover finger near board center
        val st = Stroke(SPEED)
        st.start(tp.cx, tp.cy); st.dwell(260f)                     // press & hold → the game picks the piece up
        st.moveTo(tp.cx, tp.cy - pitch * 0.6f)                     // lift
        st.moveTo(hx, hy)                                          // glide over the board
        val hoverStart = st.ms; st.dwell(1300f); val hoverEnd = st.ms
        st.moveTo(tp.cx, tp.cy + pitch * 0.3f); st.dwell(120f)     // back home → releasing there returns it to the tray
        var measured: Triple<Float, Float, Float>? = null
        val worker = Thread {
            // the HTTP round-trip adds latency before the stroke starts; sample generously across the hover window
            Thread.sleep((hoverStart + 300).toLong())
            val found = ArrayList<Triple<Float, Float, Float>>()
            val tEnd = System.currentTimeMillis() + (hoverEnd - hoverStart).toLong() + 400
            while (System.currentTimeMillis() < tEnd && found.size < 6) {
                val f = capture(); if (f != null) locateFlying(base, f, scr, piece)?.let { found.add(it) }
                Thread.sleep(60)
            }
            if (found.size >= 2) {
                val xs = found.map { it.first }.sorted(); val ys = found.map { it.second }.sorted(); val ss = found.map { it.third }.sorted()
                measured = Triple(xs[xs.size / 2], ys[ys.size / 2], ss[ss.size / 2])
            }
        }
        worker.start()
        val r = rc.swipePath(st.pts, st.totalMs)
        worker.join(5000)
        if (!r.ok) return null to "إيماءة القياس فشلت: ${r.error}"
        val m = measured ?: return null to "مش شايف القطعة وهي مرفوعة — بعيد القياس"
        val c = Cal(m.first - hx, m.second - hy, m.third)
        Log.i(TAG, "geometry: off=(${"%.0f".format(c.offX)},${"%.0f".format(c.offY)}) scale=${"%.2f".format(c.scale)}")
        return c to "تم قياس الهندسة: إزاحة (${c.offX.toInt()},${c.offY.toInt()}) مقياس ${"%.2f".format(c.scale)}"
    }

    /**
     * Place = ONE exact stroke: press-hold at the tray piece → lift → glide so that the piece's center is exactly on the
     * target cells' center (finger = target − measured offset) → dwell there so the game locks the ghost → release.
     */
    private fun placeExact(rc: RelayClient, scr: Screen, tp: com.thndr.autoplay.vision.TrayPiece, piece: com.thndr.autoplay.engine.Piece, r: Int, c: Int, cal: Cal): Pair<Boolean, String> {
        val pitch = scr.pitch
        val tx = scr.bx0 + (c + piece.w / 2f) * pitch; val ty = scr.by0 + (r + piece.h / 2f) * pitch
        val fx = tx - cal.offX; val fy = ty - cal.offY
        val st = Stroke(SPEED)
        st.start(tp.cx, tp.cy); st.dwell(260f)
        st.moveTo(tp.cx, tp.cy - pitch * 0.6f)
        st.moveTo(fx, fy)
        st.dwell(420f)
        val res = rc.swipePath(st.pts, st.totalMs)
        if (!res.ok) return false to "السحب فشل: ${res.error}"
        return true to "نزلت في مكانها بالظبط"
    }

    /**
     * Locate the lifted piece: cube-colored pixels present in `frame` but not in `base` (the vacated tray slot is
     * background now, so it never counts). Robust row/column projections give the bounding box → (centerX, centerY, scale).
     */
    private fun isCube(p: Int): Boolean {
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return mx - mn >= 100 && mx >= 170
    }
    private fun locateFlying(base: Bitmap, frame: Bitmap, scr: Screen, piece: com.thndr.autoplay.engine.Piece): Triple<Float, Float, Float>? {
        val W = frame.width; val H = frame.height
        val step = 2
        val margin = (scr.pitch * 2.5f).toInt()
        val x0 = maxOf(0, scr.bx0 - margin); val x1 = minOf(W - 1, scr.bx1 + margin)
        val y0 = maxOf(0, scr.by0 - margin); val y1 = minOf(H - 1, (scr.by1 + scr.pitch * 2.6f).toInt())  // exclude the tray itself
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val fpx = IntArray(rw * rh); frame.getPixels(fpx, 0, rw, x0, y0, rw, rh)
        val bpx = IntArray(rw * rh); base.getPixels(bpx, 0, rw, x0, y0, rw, rh)
        val w = (rw - 1) / step + 1; val h = (rh - 1) / step + 1
        val colP = IntArray(w); val rowP = IntArray(h)
        var total = 0
        for (yy in 0 until h) {
            val off = (yy * step) * rw
            for (xx in 0 until w) {
                val i = off + xx * step
                if (!isCube(fpx[i]) || isCube(bpx[i])) continue     // only NEW cube pixels vs. baseline
                colP[xx]++; rowP[yy]++; total++
            }
        }
        val cubeArea = (scr.pitch * scr.pitch) / (step * step)
        if (total < cubeArea * 0.25f * piece.size) return null
        val thr = (scr.pitch * 0.25f / step).toInt().coerceAtLeast(2)
        var cx0 = -1; var cx1 = -1; var cy0 = -1; var cy1 = -1
        for (i in 0 until w) if (colP[i] > thr) { if (cx0 < 0) cx0 = i; cx1 = i }
        for (i in 0 until h) if (rowP[i] > thr) { if (cy0 < 0) cy0 = i; cy1 = i }
        if (cx0 < 0 || cy0 < 0) return null
        val bw = (cx1 - cx0 + 1) * step.toFloat(); val bh = (cy1 - cy0 + 1) * step.toFloat()
        val scaleX = bw / (piece.w * scr.pitch); val scaleY = bh / (piece.h * scr.pitch)
        if (scaleX < 0.6f || scaleX > 1.45f || scaleY < 0.6f || scaleY > 1.45f) return null   // must look like our piece
        val cx = x0 + (cx0 + cx1 + 1) / 2f * step; val cy = y0 + (cy0 + cy1 + 1) / 2f * step
        return Triple(cx, cy, (scaleX + scaleY) / 2f)
    }

    /** Capture with overlays hidden (no parsing). */
    private fun captureClean(): Bitmap? {
        guide?.clear(); overlay?.setHiddenForCapture(true)
        Thread.sleep(200)
        var bmp = capture(); Thread.sleep(40); bmp = capture() ?: bmp
        overlay?.setHiddenForCapture(false)
        return bmp
    }

    /** Wait until the game finished animating (two identical consecutive reads), max ~2.5 s. */
    private fun waitSettled(): Screen? {
        var prev: String? = null; var last: Screen? = null
        val t0 = System.currentTimeMillis()
        Thread.sleep(220)
        while (System.currentTimeMillis() - t0 < 2500 && !stopFlag) {
            val s = cleanCapture()
            if (s != null) { val sig = s.boardString() + s.piecesFound; if (sig == prev) return s; prev = sig; last = s }
            Thread.sleep(160)
        }
        return last
    }

    /** Hide our overlays, grab the freshest frame, parse it. Null when the board is not visible. */
    private fun cleanCapture(): Screen? {
        guide?.clear(); overlay?.setHiddenForCapture(true)
        Thread.sleep(220)
        var bmp = capture(); Thread.sleep(50); bmp = capture() ?: bmp
        overlay?.setHiddenForCapture(false)
        return try { bmp?.let { ScreenParser.parse(it) } } catch (e: ScreenParser.ParseException) { null }
    }

    /** (dr,dc) shift with which the piece actually landed, or null if it did not land near the target. */
    private fun findShift(before: IntArray, after: IntArray, p: com.thndr.autoplay.engine.Piece, r: Int, c: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null; var bestHit = -1
        for (dr in -3..3) for (dc in -3..3) {
            var hit = 0; var okAll = true
            for (cell in p.cells) {
                val rr = r + cell.r + dr; val cc = c + cell.c + dc
                if (rr !in 0 until N || cc !in 0 until N) { okAll = false; break }
                val i = rr * N + cc
                if (after[i] != 0 && before[i] == 0) hit++
            }
            if (okAll && hit > bestHit) { bestHit = hit; best = dr to dc }
        }
        return if (bestHit >= p.size - 1 && best != null && (best.first != 0 || best.second != 0)) best else null
    }

    /** Auto mode: the bot drags the pieces itself via the accessibility service. */
    private fun autoLoop() {
        var lastSig = ""
        var sameCount = 0
        while (!stopFlag) {
            try {
                val bmp = capture() ?: run { Thread.sleep(200); null } ?: continue
                val scr = try { ScreenParser.parse(bmp) } catch (e: ScreenParser.ParseException) {
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
                report("بفكر… (${scr.piecesFound} قطع)")
                val plan = AI.plan(scr.board, scr.bonus, pieces, 0, 1, prefs.getInt("level", 4))
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

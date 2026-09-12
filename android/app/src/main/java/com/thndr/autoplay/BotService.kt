package com.thndr.autoplay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
    private var W = 0; private var H = 0
    private var stopFlag = false
    private val prefs by lazy { getSharedPreferences("bot", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startFg()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) setupProjection(code, data)
                overlay = OverlayController(this) { toggle() }.also { it.show() }
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

    private fun startLoop() {
        if (projection == null) { report("لازم تسمح بتسجيل الشاشة أولاً"); return }
        if (!GestureService.isRunning) { report("فعّل خدمة الوصول (Accessibility) للتطبيق"); return }
        stopFlag = false; running = true; overlay?.setRunning(true)
        worker = HandlerThread("bot").also { it.start(); handler = Handler(it.looper) }
        handler?.post { loop() }
    }

    private fun stopLoop() {
        stopFlag = true; running = false; overlay?.setRunning(false)
        worker?.quitSafely(); worker = null
        report("متوقف")
    }

    // ---------- main loop ----------
    private var idleCount = 0
    private fun loop() {
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
                val plan = AI.plan(scr.board, scr.bonus, pieces, 0, 1, prefs.getInt("level", 3))
                if (plan.gameOver || plan.moves.isEmpty()) { report("مافيش حركة ممكنة — Game Over"); Thread.sleep(1500); continue }

                // Execute only the FIRST move, then re-read the screen (robust to clears/animations)
                val mv = plan.moves[0]; val tp = scr.tray[mv.slot]!!
                val ok = performMove(scr, tp, mv.r, mv.c)
                if (!ok) { report("الإيماءة فشلت"); Thread.sleep(500); continue }
                movesDone++
                report("حركة #$movesDone: قطعة ${mv.slot + 1} → (${mv.r + 1},${mv.c + 1}) +${mv.points}")
                Thread.sleep(prefs.getInt("delay", 650).toLong())
                // verify & auto-calibrate
                verifyAndCalibrate(scr, tp, mv.r, mv.c)
            } catch (e: Throwable) {
                Log.e(TAG, "loop error", e); report("خطأ: ${e.message}"); Thread.sleep(800)
            }
        }
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
        stopLoop(); overlay?.hide(); vdisplay?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}

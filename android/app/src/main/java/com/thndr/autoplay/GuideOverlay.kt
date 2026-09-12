package com.thndr.autoplay

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.thndr.autoplay.engine.Piece
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Touch-transparent overlay. Shows the WHOLE plan (3 dim ghosts numbered 1-2-3, like the web version)
 * while only the CURRENT step is bright & pulsing with an arrow — so the player sees the big picture
 * without being distracted. Done steps disappear.
 */
class GuideOverlay(ctx: Context) : View(ctx) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

    /** One planned placement. pieceRect = where the piece currently sits in the tray (null when already placed). */
    data class Step(val piece: Piece, val pieceRect: RectF?, val trayIndex: Int, val r: Int, val c: Int, val points: Int)
    data class PlanView(
        val bx0: Float, val by0: Float, val pitch: Float,
        val steps: List<Step>, val current: Int,          // index of the step to do now
        val text: String, val sub: String?
    )
    @Volatile private var plan: PlanView? = null
    @Volatile private var message: String? = null
    @Volatile private var flash: String? = null; private var flashUntil = 0L
    private var phase = 0f

    // colors are chosen so the screen parser never mistakes overlay pixels for real cubes / bonus text
    private val pGhostBlueDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 47, 155, 255) }
    private val pGhostOrangeDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 167, 38) }
    private val pGhostBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 47, 155, 255) }
    private val pGhostOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(125, 255, 167, 38) }
    private val pEdgeDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(110, 200, 220, 240); pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(60, 220, 120) }
    private val pFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(60, 220, 120) }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.argb(235, 60, 220, 120); strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f) }
    private val pArrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 220, 120) }
    private val pBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 220, 120) }
    private val pBadgeDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 200, 210, 225) }
    private val pBadgeTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(6, 30, 60); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pTxtBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 4, 26, 51) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pTxtSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 209, 102); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pFlashBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 5, 150, 105) }

    private val ticker = object : Runnable { override fun run() { phase += 0.11f; invalidate(); if (attached) main.postDelayed(this, 40) } }

    fun show() { main.post { if (!attached) { runCatching { wm.addView(this, lp); attached = true; main.post(ticker) } } } }
    fun hide() { main.post { if (attached) { runCatching { wm.removeView(this) }; attached = false } } }
    fun setPlan(p: PlanView?) { plan = p; if (p != null) message = null; postInvalidate() }
    fun setMessage(m: String?) { message = m; if (m != null) plan = null; postInvalidate() }
    fun flash(m: String, ms: Long = 1400) { flash = m; flashUntil = System.currentTimeMillis() + ms; postInvalidate() }
    fun clear() { plan = null; message = null; postInvalidate() }

    private fun ghostRect(pv: PlanView, s: Step): RectF {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f; var maxY = 0f
        for (cell in s.piece.cells) {
            val x = pv.bx0 + (s.c + cell.c) * pv.pitch; val y = pv.by0 + (s.r + cell.r) * pv.pitch
            minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x + pv.pitch); maxY = maxOf(maxY, y + pv.pitch)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val loc = IntArray(2); getLocationOnScreen(loc); val yOff = loc[1].toFloat()
        val pv = plan; val msg = message
        if (pv == null) { if (msg != null) drawBanner(cv, msg, null, d); drawFlash(cv, d); return }

        cv.save(); cv.translate(0f, -yOff)
        val pulse = 0.5f + 0.5f * sin(phase.toDouble()).toFloat()
        val pad = pv.pitch * 0.08f; val rad = pv.pitch * 0.15f

        // ---- 1) all remaining steps as dim ghosts (plan overview), current one bright ----
        for ((i, s) in pv.steps.withIndex()) {
            if (i < pv.current) continue                       // already placed
            val active = i == pv.current
            for (cell in s.piece.cells) {
                val x = pv.bx0 + (s.c + cell.c) * pv.pitch; val y = pv.by0 + (s.r + cell.r) * pv.pitch
                val rf = RectF(x + pad, y + pad, x + pv.pitch - pad, y + pv.pitch - pad)
                val paint = if (active) (if (cell.v == 2) pGhostOrange else pGhostBlue) else (if (cell.v == 2) pGhostOrangeDim else pGhostBlueDim)
                cv.drawRoundRect(rf, rad, rad, paint)
            }
            val gr = ghostRect(pv, s); gr.inset(-3f, -3f)
            if (active) { pEdge.alpha = (150 + 105 * pulse).toInt(); cv.drawRoundRect(gr, rad, rad, pEdge) }
            else cv.drawRoundRect(gr, rad, rad, pEdgeDim)
            // number badge at the ghost's top-right corner
            val br = (if (active) 14f else 11f) * d
            cv.drawCircle(gr.right, gr.top, br, if (active) pBadge else pBadgeDim)
            pBadgeTxt.textSize = (if (active) 16f else 12f) * d
            cv.drawText("${i + 1}", gr.right, gr.top + pBadgeTxt.textSize * 0.36f, pBadgeTxt)
        }

        // ---- 2) tray: number badges on all remaining pieces; frame + arrow for the current one ----
        for ((i, s) in pv.steps.withIndex()) {
            if (i < pv.current) continue
            val pr = s.pieceRect ?: continue
            val active = i == pv.current
            val fr = RectF(pr); fr.inset(-10f * d, -10f * d)
            if (active) {
                pFrame.alpha = (160 + 95 * pulse).toInt()
                cv.drawRoundRect(fr, 12f * d, 12f * d, pFrame)
                // arrow to the target ghost
                val gr = ghostRect(pv, s)
                val sx = fr.centerX(); val sy = fr.top; val tx = gr.centerX(); val ty = gr.bottom + 6f
                val ang = atan2((ty - sy).toDouble(), (tx - sx).toDouble()).toFloat()
                val path = Path(); path.moveTo(sx, sy)
                path.quadTo((sx + tx) / 2 + (if (tx > sx) -1 else 1) * 18f * d, (sy + ty) / 2, tx - cos(ang) * 10f, ty - sin(ang) * 10f)
                cv.drawPath(path, pArrow)
                val hl = 16f * d; val head = Path(); head.moveTo(tx, ty)
                head.lineTo(tx - hl * cos(ang - 0.45f), ty - hl * sin(ang - 0.45f)); head.lineTo(tx - hl * cos(ang + 0.45f), ty - hl * sin(ang + 0.45f)); head.close()
                cv.drawPath(head, pArrowHead)
            }
            val br = (if (active) 14f else 11f) * d
            cv.drawCircle(fr.left, fr.top, br, if (active) pBadge else pBadgeDim)
            pBadgeTxt.textSize = (if (active) 16f else 12f) * d
            cv.drawText("${i + 1}", fr.left, fr.top + pBadgeTxt.textSize * 0.36f, pBadgeTxt)
        }
        cv.restore()

        drawBanner(cv, pv.text, pv.sub, d)
        drawFlash(cv, d)
    }

    private fun drawBanner(cv: Canvas, main: String, sub: String?, d: Float) {
        val w = width.toFloat(); val top = 8f * d
        val hgt = if (sub != null) 62f * d else 44f * d
        cv.drawRoundRect(RectF(16f * d, top, w - 16f * d, top + hgt), 16f * d, 16f * d, pTxtBg)
        pTxt.textSize = 17f * d; cv.drawText(main, w / 2, top + 27f * d, pTxt)
        if (sub != null) { pTxtSub.textSize = 13f * d; cv.drawText(sub, w / 2, top + 50f * d, pTxtSub) }
    }
    private fun drawFlash(cv: Canvas, d: Float) {
        val f = flash ?: return
        if (System.currentTimeMillis() > flashUntil) { flash = null; return }
        val w = width.toFloat(); val cy = height * 0.42f
        pTxt.textSize = 20f * d
        val tw = pTxt.measureText(f) + 40f * d
        cv.drawRoundRect(RectF(w / 2 - tw / 2, cy - 26f * d, w / 2 + tw / 2, cy + 26f * d), 18f * d, 18f * d, pFlashBg)
        cv.drawText(f, w / 2, cy + 7f * d, pTxt)
    }
}

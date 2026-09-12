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
 * Full-screen, touch-transparent overlay that shows the player EXACTLY where to drop the current piece:
 *  - white pulsing frame + number around the tray piece to pick up
 *  - glowing ghost of the piece on the target board cells (blue/orange as the real cubes)
 *  - arrow from the piece to the target + short Arabic instruction
 * One piece at a time — never distracting.
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

    // ---- state (screen pixel coords) ----
    data class Hint(
        val piece: Piece, val pieceRect: RectF, val pieceIndex: Int,          // tray piece
        val bx0: Float, val by0: Float, val pitch: Float, val r: Int, val c: Int, // target on board
        val text: String, val step: Int, val total: Int, val points: Int
    )
    @Volatile private var hint: Hint? = null
    @Volatile private var message: String? = null
    private var phase = 0f
    private var yOffset = 0 // status-bar offset when the overlay isn't drawn from y=0

    private val pFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE }
    private val pGhostBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(85, 47, 155, 255) }   // blends to ~(15,80,135): not 'blue' for the parser
    private val pGhostOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(120, 255, 167, 38) }  // blends to ~(120,103,61): not 'orange' for the parser
    private val pGhostEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(60, 220, 120) }
    private val pDim = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.argb(235, 60, 220, 120); strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f) }
    private val pArrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(60, 220, 120) }
    private val pBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pBadgeTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 44, 87); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pTxtBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 4, 26, 51) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pTxtSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 209, 102); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pCellNum = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    private val ticker = object : Runnable { override fun run() { phase += 0.12f; invalidate(); if (attached) main.postDelayed(this, 40) } }

    fun show() { main.post { if (!attached) { runCatching { wm.addView(this, lp); attached = true; main.post(ticker) } } } }
    fun hide() { main.post { if (attached) { runCatching { wm.removeView(this) }; attached = false } } }
    fun setHint(h: Hint?) { hint = h; if (h != null) message = null; postInvalidate() }
    fun setMessage(m: String?) { message = m; postInvalidate() }
    fun clear() { hint = null; message = null; postInvalidate() }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        // The overlay window may not start at the very top (status bar). Map screen coords -> view coords.
        val loc = IntArray(2); getLocationOnScreen(loc); yOffset = loc[1]
        val h = hint
        val msg = message
        if (h == null) {
            if (msg != null) drawBanner(cv, msg, null, d)
            return
        }
        cv.save(); cv.translate(0f, -yOffset.toFloat())
        val pulse = 0.5f + 0.5f * sin(phase.toDouble()).toFloat()

        // 1) target ghost on the board
        val pad = h.pitch * 0.08f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f; var maxY = 0f
        for (cell in h.piece.cells) {
            val x = h.bx0 + (h.c + cell.c) * h.pitch; val y = h.by0 + (h.r + cell.r) * h.pitch
            val rf = RectF(x + pad, y + pad, x + h.pitch - pad, y + h.pitch - pad)
            cv.drawRoundRect(rf, h.pitch * 0.15f, h.pitch * 0.15f, if (cell.v == 2) pGhostOrange else pGhostBlue)
            minX = minOf(minX, rf.left); minY = minOf(minY, rf.top); maxX = maxOf(maxX, rf.right); maxY = maxOf(maxY, rf.bottom)
        }
        pGhostEdge.alpha = (140 + 115 * pulse).toInt()
        val outline = RectF(minX - 4f, minY - 4f, maxX + 4f, maxY + 4f)
        cv.drawRoundRect(outline, h.pitch * 0.2f, h.pitch * 0.2f, pGhostEdge)

        // 2) frame around the tray piece
        pFrame.alpha = (170 + 85 * pulse).toInt()
        val pr = RectF(h.pieceRect); pr.inset(-12f * d, -12f * d)
        cv.drawRoundRect(pr, 14f * d, 14f * d, pFrame)
        // number badge
        val br = 15f * d
        cv.drawCircle(pr.left, pr.top, br, pBadgeBg)
        pBadgeTxt.textSize = 17f * d
        cv.drawText("${h.pieceIndex + 1}", pr.left, pr.top + 6f * d, pBadgeTxt)

        // 3) arrow from piece to target
        val sx = pr.centerX(); val sy = pr.top
        val tx = outline.centerX(); val ty = outline.bottom
        val ang = atan2((ty - sy).toDouble(), (tx - sx).toDouble()).toFloat()
        val ex = tx - cos(ang) * 10f; val ey = ty - sin(ang) * 10f
        val path = Path(); path.moveTo(sx, sy)
        // slight curve
        val mx = (sx + tx) / 2 + (if (tx > sx) -1 else 1) * 60f * d * 0.3f; val my = (sy + ty) / 2
        path.quadTo(mx, my, ex, ey)
        cv.drawPath(path, pArrow)
        val hl = 16f * d
        val head = Path(); head.moveTo(tx, ty)
        head.lineTo(tx - hl * cos(ang - 0.45f), ty - hl * sin(ang - 0.45f)); head.lineTo(tx - hl * cos(ang + 0.45f), ty - hl * sin(ang + 0.45f)); head.close()
        cv.drawPath(head, pArrowHead)
        cv.restore()

        // 4) instruction banner (fixed under the status bar)
        drawBanner(cv, h.text, "الخطوة ${h.step}/${h.total}   •   +${h.points} نقطة متوقعة", d)
    }

    private fun drawBanner(cv: Canvas, main: String, sub: String?, d: Float) {
        val w = width.toFloat(); val top = 8f * d
        val hgt = if (sub != null) 62f * d else 44f * d
        val rect = RectF(16f * d, top, w - 16f * d, top + hgt)
        cv.drawRoundRect(rect, 16f * d, 16f * d, pTxtBg)
        pTxt.textSize = 17f * d
        cv.drawText(main, w / 2, top + 27f * d, pTxt)
        if (sub != null) { pTxtSub.textSize = 13f * d; cv.drawText(sub, w / 2, top + 50f * d, pTxtSub) }
    }
}

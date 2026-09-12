package com.thndr.autoplay

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.thndr.autoplay.engine.Cell
import com.thndr.autoplay.engine.Piece

/** Touchable overlay: shows the 3 pieces the bot READ on 5x5 mini grids; user fixes by tapping, then OK. */
class PieceEditorOverlay(ctx: Context, private val onConfirm: (List<Piece?>) -> Unit, private val onCancel: () -> Unit) : View(ctx) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private val G = 5
    private val grids = Array(3) { IntArray(G * G) }
    private var panelTop = 0f; private var cellPx = 0f; private var gridLeft = FloatArray(3); private var gridTop = 0f
    private var btnOk = RectF(); private var btnCancel = RectF(); private var btnClear = Array(3) { RectF() }
    private val pDim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val pPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 36, 70) }
    private val pCell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 54, 100) }
    private val pBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(47, 155, 255) }
    private val pOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 167, 38) }
    private val pShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 190, 225); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pOk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(5, 150, 105) }
    private val pCancel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 80, 110) }
    private val pBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pBadgeTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 44, 87); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun show(pieces: List<Piece?>) {
        for (i in 0 until 3) {
            grids[i].fill(0)
            val p = pieces.getOrNull(i) ?: continue
            val offR = (G - p.h) / 2; val offC = (G - p.w) / 2
            for (c in p.cells) { val r = c.r + offR; val cc = c.c + offC; if (r in 0 until G && cc in 0 until G) grids[i][r * G + cc] = c.v }
        }
        main.post { if (!attached) { runCatching { wm.addView(this, lp); attached = true } }; invalidate() }
    }
    fun hide() { main.post { if (attached) { runCatching { wm.removeView(this) }; attached = false } } }
    val isShowing get() = attached

    private fun layoutMetrics() {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        cellPx = minOf((w - 48 * d) / 3f / G, 30 * d)
        val gw = cellPx * G
        val gapX = (w - 3 * gw) / 4f
        for (i in 0 until 3) gridLeft[i] = gapX + i * (gw + gapX)
        val panelH = 84 * d + gw + 60 * d + 64 * d
        panelTop = height - panelH - 24 * d
        gridTop = panelTop + 84 * d
        val by = gridTop + gw + 12 * d
        for (i in 0 until 3) btnClear[i] = RectF(gridLeft[i], by, gridLeft[i] + gw, by + 36 * d)
        val bty = by + 48 * d
        btnCancel = RectF(16 * d, bty, w / 2 - 6 * d, bty + 52 * d)
        btnOk = RectF(w / 2 + 6 * d, bty, w - 16 * d, bty + 52 * d)
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        layoutMetrics()
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pDim)
        cv.drawRoundRect(RectF(8 * d, panelTop, width - 8 * d, height - 12 * d), 22 * d, 22 * d, pPanel)
        pTxt.textSize = 18 * d
        cv.drawText("هل القطع دي مطابقة للي في اللعبة؟", width / 2f, panelTop + 30 * d, pTxt)
        pSub.textSize = 13 * d
        cv.drawText("اضغط أي مربع لتعديله: فاضي ← أزرق ← أصفر", width / 2f, panelTop + 54 * d, pSub)
        cv.drawText("(هيتم التخطيط بالأشكال دي بالظبط)", width / 2f, panelTop + 72 * d, pSub)
        for (i in 0 until 3) {
            val gx = gridLeft[i]
            for (r in 0 until G) for (c in 0 until G) {
                val x = gx + c * cellPx; val y = gridTop + r * cellPx
                val rf = RectF(x + 1.5f * d, y + 1.5f * d, x + cellPx - 1.5f * d, y + cellPx - 1.5f * d)
                val v = grids[i][r * G + c]
                cv.drawRoundRect(rf, 4 * d, 4 * d, when (v) { 1 -> pBlue; 2 -> pOrange; else -> pCell })
                if (v != 0) {
                    cv.drawRoundRect(RectF(rf.left + rf.width() * 0.2f, rf.top + rf.height() * 0.2f, rf.left + rf.width() * 0.36f, rf.top + rf.height() * 0.3f), 2f, 2f, pShine)
                    cv.drawRoundRect(RectF(rf.left + rf.width() * 0.44f, rf.top + rf.height() * 0.2f, rf.left + rf.width() * 0.6f, rf.top + rf.height() * 0.3f), 2f, 2f, pShine)
                }
            }
            cv.drawCircle(gx + 2 * d, gridTop + 2 * d, 12 * d, pBadge); pBadgeTxt.textSize = 13 * d
            cv.drawText("${i + 1}", gx + 2 * d, gridTop + 6.5f * d, pBadgeTxt)
            cv.drawRoundRect(btnClear[i], 10 * d, 10 * d, pCancel); pTxt.textSize = 13 * d
            cv.drawText(if (grids[i].any { it != 0 }) "مسح" else "فاضية", btnClear[i].centerX(), btnClear[i].centerY() + 5 * d, pTxt)
        }
        cv.drawRoundRect(btnCancel, 14 * d, 14 * d, pCancel); cv.drawRoundRect(btnOk, 14 * d, 14 * d, pOk)
        pTxt.textSize = 16 * d
        cv.drawText("إلغاء", btnCancel.centerX(), btnCancel.centerY() + 6 * d, pTxt)
        cv.drawText("✓ تمام — اعمل الخطة", btnOk.centerX(), btnOk.centerY() + 6 * d, pTxt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x; val y = e.y
        if (btnOk.contains(x, y)) { onConfirm(toPieces()); return true }
        if (btnCancel.contains(x, y)) { onCancel(); return true }
        for (i in 0 until 3) if (btnClear[i].contains(x, y)) { grids[i].fill(0); invalidate(); return true }
        val gw = cellPx * G
        for (i in 0 until 3) {
            val gx = gridLeft[i]
            if (x >= gx && x < gx + gw && y >= gridTop && y < gridTop + gw) {
                val c = ((x - gx) / cellPx).toInt(); val r = ((y - gridTop) / cellPx).toInt()
                val k = r * G + c; grids[i][k] = (grids[i][k] + 1) % 3; invalidate(); return true
            }
        }
        return true
    }

    private fun toPieces(): List<Piece?> = (0 until 3).map { i ->
        val cells = ArrayList<Cell>()
        for (r in 0 until G) for (c in 0 until G) { val v = grids[i][r * G + c]; if (v != 0) cells.add(Cell(r, c, v)) }
        if (cells.isEmpty()) null else Piece(cells)
    }
}

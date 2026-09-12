package com.thndr.autoplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating ▶/■ button + status line drawn over the game. Draggable. */
class OverlayController(private val ctx: Context, private val onToggle: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private lateinit var btn: TextView
    private lateinit var status: TextView
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

    fun show() {
        main.post {
            if (root != null) return@post
            val d = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            btn = TextView(ctx).apply {
                text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1B6BC0")); setStroke((3 * d).toInt(), Color.parseColor("#3A8FE6")) }
            }
            status = TextView(ctx).apply {
                text = BotService.status; textSize = 12f; setTextColor(Color.WHITE); maxLines = 2
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.parseColor("#CC041A33")) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * d).toInt() }
            }
            row.addView(btn); row.addView(status)
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            row.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                    MotionEvent.ACTION_MOVE -> { val dx = e.rawX - sx; val dy = e.rawY - sy; if (abs(dx) > 8 || abs(dy) > 8) moved = true; lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); wm.updateViewLayout(root, lp); true }
                    MotionEvent.ACTION_UP -> { if (!moved) onToggle(); true }
                    else -> false
                }
            }
            root = row
            wm.addView(row, lp)
            BotService.listener = { s -> main.post { if (root != null) status.text = s } }
        }
    }
    fun setRunning(r: Boolean) { main.post { if (root != null) { btn.text = if (r) "■" else "▶"; (btn.background as GradientDrawable).setColor(Color.parseColor(if (r) "#E53935" else "#1B6BC0")) } } }
    fun hide() { main.post { root?.let { runCatching { wm.removeView(it) } }; root = null; BotService.listener = null } }
}

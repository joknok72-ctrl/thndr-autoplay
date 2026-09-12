package com.thndr.autoplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Performs drag gestures on behalf of the bot (finger simulation). */
class GestureService : AccessibilityService() {
    companion object {
        @Volatile var instance: GestureService? = null
        val isRunning get() = instance != null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /**
     * Drag from (x0,y0) to (x1,y1): press & hold, move smoothly, settle, release.
     * Returns true when the system reports the gesture completed.
     */
    fun drag(x0: Float, y0: Float, x1: Float, y1: Float, holdMs: Long = 180, moveMs: Long = 450, settleMs: Long = 220): Boolean {
        val latch = CountDownLatch(1); var ok = false
        val b = GestureDescription.Builder()
        val p1 = Path(); p1.moveTo(x0, y0); p1.lineTo(x0 + 1f, y0 + 1f)
        var s = GestureDescription.StrokeDescription(p1, 0, holdMs, true)
        b.addStroke(s)
        val p2 = Path(); p2.moveTo(x0 + 1f, y0 + 1f); p2.lineTo(x1, y1)
        s = s.continueStroke(p2, holdMs, moveMs, true); b.addStroke(s)
        val p3 = Path(); p3.moveTo(x1, y1); p3.lineTo(x1, y1 + 0.5f)
        s = s.continueStroke(p3, holdMs + moveMs, settleMs, false); b.addStroke(s)
        val dispatched = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(holdMs + moveMs + settleMs + 1500, TimeUnit.MILLISECONDS)
        return ok
    }

    fun tap(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1); var ok = false
        val p = Path(); p.moveTo(x, y)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        if (!dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null)) return false
        latch.await(1000, TimeUnit.MILLISECONDS); return ok
    }
}

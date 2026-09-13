package com.thndr.autoplay

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin REST client for the Device Relay server (https://github.com/joknok72-ctrl/device-relay).
 *
 * The Device Relay Android app (installed on the same phone) executes gestures through its own
 * AccessibilityService, which uses the proven two-gesture drag (hold → continueStroke) and the
 * on-device `combo` primitive (down → move → wait → up). We only need: is the phone online, and
 * "drag from A to B". Everything else (vision, planning) stays inside THNDR AutoPlay.
 */
class RelayClient(server: String, private val token: String, val deviceId: String) {
    companion object {
        const val TAG = "ThndrRelay"
        const val DEFAULT_SERVER = "https://device-relay.cracknew37.workers.dev"
    }
    val base = server.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }

    class Result(val ok: Boolean, val error: String? = null, val json: JSONObject? = null) {
        override fun toString() = if (ok) "ok" else "fail: $error"
    }

    private fun request(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 20000): Result {
        return try {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000; readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && (json == null || json.optBoolean("ok", true))) Result(true, null, json)
            else Result(false, json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code ${text.take(120)}", json)
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e); Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Server reachable? (no auth needed) */
    fun health(): Result = request("GET", "/api/health")

    /** Devices this token can see: list of (id, online). */
    fun devices(): List<Pair<String, Boolean>> {
        val r = request("GET", "/api/devices")
        val arr: JSONArray = r.json?.optJSONArray("devices") ?: return emptyList()
        val out = ArrayList<Pair<String, Boolean>>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optString("deviceId", d.optString("id", "")); if (id.isBlank()) continue
            out.add(id to (d.optBoolean("online", false) || d.optString("status") == "online"))
        }
        return out
    }

    /** Is our device online right now? Returns (online, detail). */
    fun online(): Pair<Boolean, String> {
        if (deviceId.isBlank()) return false to "Device ID فاضي"
        val r = request("GET", "/api/devices/$deviceId")
        if (!r.ok) return false to (r.error ?: "?")
        val j = r.json ?: return false to "رد غير مفهوم"
        val on = j.optBoolean("online", false) || j.optString("status") == "online"
        val model = j.optString("model", "")
        val acc = !j.has("accessibilityEnabled") || j.optBoolean("accessibilityEnabled", true)
        return when {
            !on -> false to "الموبايل غير متصل بالسيرفر — افتح تطبيق Device Relay"
            !acc -> false to "Device Relay متصل لكن خدمة الوصول بتاعته مقفولة — فعّلها"
            else -> true to "متصل ✅ $model"
        }
    }

    fun command(action: JSONObject, timeoutMs: Int = 25000): Result =
        request("POST", "/api/devices/$deviceId/command", JSONObject().put("action", action).put("wait", true), timeoutMs)

    fun tap(x: Float, y: Float): Result = command(JSONObject().put("type", "tap").put("x", x).put("y", y))

    /**
     * Drag with a real press-hold, smooth move, short settle at the target and release.
     * 1) `combo` (on-device script, exact timing)  2) fallback: plain `drag` action.
     */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, moveMs: Long, settleMs: Long): Result {
        val steps = JSONArray()
            .put(JSONObject().put("op", "down").put("finger", 0).put("x", x1).put("y", y1).put("duration", holdMs.coerceIn(20, 1000)))
            .put(JSONObject().put("op", "move").put("finger", 0).put("x", (x1 + x2) / 2f).put("y", (y1 + y2) / 2f).put("duration", (moveMs / 2).coerceAtLeast(40)))
            .put(JSONObject().put("op", "move").put("finger", 0).put("x", x2).put("y", y2).put("duration", (moveMs / 2).coerceAtLeast(40)))
            .put(JSONObject().put("op", "wait").put("duration", settleMs.coerceIn(0, 2000)))
            .put(JSONObject().put("op", "up").put("finger", 0))
        val combo = command(JSONObject().put("type", "combo").put("combo", steps), (holdMs + moveMs + settleMs + 20000).toInt())
        if (combo.ok) return combo
        Log.w(TAG, "combo failed (${combo.error}) → fallback drag")
        return command(JSONObject().put("type", "drag").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            .put("holdMs", holdMs.coerceIn(50, 5000)).put("duration", (moveMs + settleMs).coerceIn(50, 10000)), (holdMs + moveMs + settleMs + 20000).toInt())
    }
}

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

    /** Safety: lift any finger Device Relay might still be holding from an earlier session. */
    fun releaseAll(): Result = command(JSONObject().put("type", "finger_up").put("finger", -1))

    /**
     * Drag = the relay's proven two-gesture drag (press-hold as one stroke, then continueStroke to the target).
     * Exactly what a finger does: press, hold a moment, move, release.
     */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, moveMs: Long): Result =
        command(JSONObject().put("type", "drag").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            .put("holdMs", holdMs.coerceIn(50, 5000)).put("duration", moveMs.coerceIn(50, 10000)), (holdMs + moveMs + 20000).toInt())

    /**
     * ONE continuous stroke through many points (Android moves along the path at constant speed, so a dense
     * zig-zag around a point = the finger "dwells" there). Used for the measurement pass: lift a piece, hover it
     * over the board for ~1.3 s while we photograph it, then bring it back to the tray and release (no placement).
     */
    fun swipePath(points: List<Pair<Float, Float>>, durationMs: Long): Result {
        val arr = JSONArray()
        for ((x, y) in points) arr.put(JSONObject().put("x", x).put("y", y))
        return command(JSONObject().put("type", "swipe_path").put("points", arr).put("duration", durationMs.coerceIn(50, 30000)), (durationMs + 20000).toInt())
    }
}

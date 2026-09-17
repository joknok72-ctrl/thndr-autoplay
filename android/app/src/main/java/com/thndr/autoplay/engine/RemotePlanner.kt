package com.thndr.autoplay.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sends the board to the plan server (same engine.js/ai.js as the website, running on a real CPU) and returns the plan.
 *  Throws on any network/server failure — the caller falls back to the on-device planner. */
object RemotePlanner {
    const val DEFAULT_URL = "https://thndr-plan-production-b4cb.up.railway.app"          // Railway: always-on, fastest measured
    const val BACKUP_URL = "https://thndr-plan.fly.dev"                                // Fly: backup (trial account → 5-min recycling)
    /** Same server behind the game's Cloudflare domain — used automatically when fly.dev is unreachable on the phone's network. */
    const val PROXY_URL = "https://thndr-ai-block.pages.dev"
    var lastMs = 0L
    var lastUrl = ""
    /** (url, attempt, error) — lets the UI show that a retry is happening instead of looking frozen. */
    var onRetry: ((String, Int, String) -> Unit)? = null

    /** Try the configured URL, then the Cloudflare proxy. */
    fun planAny(baseUrl: String, board: IntArray, bonus: IntArray, pieces: List<Piece?>, mult: Int, gameLevel: Int,
                level: Int, deep: Boolean, gameScore: Int, fillMoves: Int): Plan {
        val urls = (listOf(baseUrl.trimEnd('/'), BACKUP_URL, PROXY_URL)).distinct()
        var last: Exception? = null
        // The server machine can be recycled mid-request (trial hosting) → it answers 503 {retry} or drops the connection.
        // Retry the same URL a few times (fresh machine in ~2 s) before moving to the Cloudflare route, and only then give up.
        for (u in urls) for (attempt in 0 until 2) {
            try { val p = plan(u, board, bonus, pieces, mult, gameLevel, level, deep, gameScore, fillMoves); lastUrl = u; return p }
            catch (e: Exception) { last = e; onRetry?.invoke(u, attempt + 1, e.message ?: "?"); Thread.sleep(2500) }
        }
        throw last ?: IllegalStateException("no server")
    }

    fun plan(baseUrl: String, board: IntArray, bonus: IntArray, pieces: List<Piece?>, mult: Int, gameLevel: Int,
             level: Int, deep: Boolean, gameScore: Int, fillMoves: Int, timeoutMs: Int = 240_000): Plan {
        val body = JSONObject()
        body.put("board", JSONArray(board.toList())); body.put("bonus", JSONArray(bonus.toList()))
        val ps = JSONArray()
        for (p in pieces) {
            if (p == null) { ps.put(JSONObject.NULL); continue }
            val cells = JSONArray(); for (c in p.cells) cells.put(JSONObject().put("r", c.r).put("c", c.c).put("v", c.v))
            ps.put(JSONObject().put("cells", cells).put("yv", p.yv))
        }
        body.put("pieces", ps); body.put("mult", mult); body.put("gameLevel", gameLevel); body.put("score", gameScore)
        body.put("level", level); body.put("deep", deep); body.put("fillMoves", fillMoves)
        val url = URL(baseUrl.trimEnd('/') + "/api/plan")
        val t0 = System.currentTimeMillis()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 25_000; conn.readTimeout = timeoutMs
            conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: throw IllegalStateException("HTTP $code"))
            val text = stream.use { s -> val bo = ByteArrayOutputStream(); s.copyTo(bo); bo.toString("UTF-8") }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(120)}")
            val j = JSONObject(text)
            if (j.has("error")) throw IllegalStateException(j.getString("error"))
            val ms = j.optJSONArray("moves") ?: JSONArray()
            val moves = ArrayList<Move>()
            for (i in 0 until ms.length()) { val m = ms.getJSONObject(i); moves.add(Move(m.getInt("slot"), m.getInt("r"), m.getInt("c"), m.optInt("points"), m.optInt("lines"))) }
            lastMs = System.currentTimeMillis() - t0
            return Plan(moves, j.optInt("total"), j.optBoolean("gameOver"), j.optInt("finalMult", mult))
        } finally { conn.disconnect() }
    }

    /** Quick reachability probe (GET /health) — returns server CPU count or -1. */
    fun pingAny(baseUrl: String): Pair<Int, String> {
        for (u in listOf(baseUrl.trimEnd('/'), BACKUP_URL, PROXY_URL).distinct()) { val n = ping(u); if (n > 0) return n to u }
        return -1 to ""
    }
    fun ping(baseUrl: String): Int = try {
        val conn = URL(baseUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 25000
        val txt = conn.inputStream.use { s -> val bo = ByteArrayOutputStream(); s.copyTo(bo); bo.toString("UTF-8") }
        conn.disconnect(); JSONObject(txt).optInt("cpus", 1)
    } catch (_: Exception) { -1 }
}

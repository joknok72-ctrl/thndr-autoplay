package com.thndr.autoplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Automatic game recorder — replaces "send me 70 screenshots".
 * Every time a plan is made we store: the parsed screen (board / bonus / pieces / mult / level), the plan, and a small
 * JPEG of the screen. When the game ends (or the user taps "share log") everything is zipped into one file.
 */
object GameLog {
    private const val MAX_SHOTS = 90
    private val entries = JSONArray()
    private var shots = 0
    private var dir: File? = null
    var gameId: String = ""; private set

    fun newGame(ctx: Context) {
        gameId = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        dir = File(ctx.cacheDir, "gamelog/$gameId").also { it.deleteRecursively(); it.mkdirs() }
        while (entries.length() > 0) entries.remove(0)
        shots = 0
    }

    fun record(ctx: Context, scr: com.thndr.autoplay.vision.Screen, pieces: List<com.thndr.autoplay.engine.Piece?>, plan: com.thndr.autoplay.engine.Plan, bmp: Bitmap?, note: String = "") {
        if (dir == null) newGame(ctx)
        val e = JSONObject()
        e.put("t", System.currentTimeMillis()); e.put("level", scr.level); e.put("mult", scr.mult)
        e.put("board", scr.board.joinToString("")); e.put("bonus", JSONArray(scr.bonus.toList()))
        e.put("pieces", JSONArray(pieces.map { p -> p?.let { JSONArray(it.cells.map { c -> "${c.r},${c.c},${c.v}" }) } ?: JSONObject.NULL }))
        e.put("plan", JSONArray(plan.moves.map { m -> JSONObject().put("slot", m.slot).put("r", m.r).put("c", m.c).put("pts", m.points).put("lines", m.lines) }))
        e.put("planTotal", plan.total); e.put("note", note)
        if (bmp != null && shots < MAX_SHOTS) {
            val f = File(dir, "s%03d.jpg".format(java.util.Locale.US, entries.length()))
            runCatching {
                val scale = 720f / bmp.width; val small = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * scale).toInt(), true)
                FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                e.put("shot", f.name); shots++
            }
        }
        entries.put(e)
        runCatching { File(dir, "log.json").writeText(JSONObject().put("game", gameId).put("entries", entries).toString()) }
    }

    fun count() = entries.length()

    /** Snapshot AFTER a single piece was placed (step 1..3 of the current plan): board / bonus / mult / tray + a JPEG.
     *  This is what lets us see exactly WHEN a bonus cell grows or a new one appears — after which piece, placed where. */
    fun recordStep(ctx: Context, scr: com.thndr.autoplay.vision.Screen?, step: Int, slot: Int, r: Int, c: Int, piece: com.thndr.autoplay.engine.Piece?, bmp: Bitmap?) {
        if (dir == null) newGame(ctx)
        val e = JSONObject()
        e.put("t", System.currentTimeMillis()); e.put("kind", "step"); e.put("step", step)
        e.put("placed", JSONObject().put("slot", slot).put("r", r).put("c", c).put("cells", piece?.let { JSONArray(it.cells.map { cc -> "${cc.r},${cc.c},${cc.v}" }) } ?: JSONObject.NULL))
        if (scr != null) {
            e.put("level", scr.level); e.put("mult", scr.mult)
            e.put("board", scr.board.joinToString("")); e.put("bonus", JSONArray(scr.bonus.toList()))
            e.put("tray", JSONArray(scr.tray.map { tp -> tp?.piece?.let { JSONArray(it.cells.map { cc -> "${cc.r},${cc.c},${cc.v}" }) } ?: JSONObject.NULL }))
        } else e.put("parse", "failed")
        if (bmp != null && shots < MAX_SHOTS * 4) {
            val f = File(dir, "s%03d_%d.jpg".format(java.util.Locale.US, entries.length(), step))
            runCatching {
                val scale = 720f / bmp.width; val small = Bitmap.createScaledBitmap(bmp, 720, (bmp.height * scale).toInt(), true)
                FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                e.put("shot", f.name); shots++
            }
        }
        entries.put(e)
        runCatching { File(dir, "log.json").writeText(JSONObject().put("game", gameId).put("entries", entries).toString()) }
    }

    /** Zip everything and open the share sheet (WhatsApp / Telegram / Drive …). */
    fun share(ctx: Context): Boolean {
        val d = dir ?: return false
        if (entries.length() == 0) return false
        val out = File(ctx.cacheDir, "gamelog/thndr_game_$gameId.zip")
        runCatching {
            ZipOutputStream(FileOutputStream(out)).use { z ->
                d.listFiles()?.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
        }.getOrElse { return false }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "THNDR game log $gameId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "مشاركة سجل الجولة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.getOrElse { return false }
        return true
    }
}

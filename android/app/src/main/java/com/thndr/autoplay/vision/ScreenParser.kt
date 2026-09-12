package com.thndr.autoplay.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.thndr.autoplay.engine.Cell
import com.thndr.autoplay.engine.N
import com.thndr.autoplay.engine.Piece
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the THNDR Block screen: board rect, 9x9 cells (blue/orange/empty/bonus), and the 3 tray pieces.
 * Calibrated on the reference screenshots (cell bg ≈ #012F51, page ≈ #003B65, blue ≈ #31AAFF, orange ≈ #FFA726).
 */
data class TrayPiece(val piece: Piece, val cx: Float, val cy: Float, val cubePx: Float, val x0: Int, val y0: Int, val x1: Int, val y1: Int)
data class Screen(
    val board: IntArray, val bonus: IntArray,
    val bx0: Int, val by0: Int, val bx1: Int, val by1: Int, val pitch: Float,
    val tray: List<TrayPiece?>, val width: Int, val height: Int
) {
    fun cellCenter(r: Int, c: Int): Pair<Float, Float> = Pair(bx0 + (c + 0.5f) * pitch, by0 + (r + 0.5f) * pitch)
    val piecesFound get() = tray.count { it != null }
    fun boardString(): String { val sb = StringBuilder(); for (r in 0 until N) { for (c in 0 until N) { val i = r * N + c; sb.append(when { board[i] == 1 -> '#'; board[i] == 2 -> 'O'; bonus[i] != 0 -> '$'; else -> '.' }) }; sb.append('\n') }; return sb.toString() }
}

object ScreenParser {
    class ParseException(msg: String) : Exception(msg)

    // ---- Color classifiers ----
    private fun isCellBg(p: Int): Boolean { val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p); return r < 20 && g in 36..62 && b in 68..104 }
    fun isBlue(p: Int): Boolean { val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p); return b > 185 && r < 140 && g in 105..240 && b - r > 85 }
    fun isOrange(p: Int): Boolean { val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p); return r > 185 && g in 95..220 && b < 130 && r - b > 95 }
    private fun isGrayText(p: Int): Boolean { val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p); return r > 80 && g > 100 && b > 130 && abs(r - g) < 60 && b - r < 90 && r + g + b > 300 }

    private fun clusters(idx: List<Int>, gap: Int): List<IntArray> {
        if (idx.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>(); var s = idx[0]; var p = idx[0]
        for (k in 1 until idx.size) { val x = idx[k]; if (x - p > gap) { out.add(intArrayOf(s, p)); s = x }; p = x }
        out.add(intArrayOf(s, p)); return out
    }

    fun parse(bmp: Bitmap): Screen {
        val W = bmp.width; val H = bmp.height
        val px = IntArray(W * H); bmp.getPixels(px, 0, W, 0, 0, W, H)
        val boardish = BooleanArray(W * H)
        val blue = BooleanArray(W * H); val orange = BooleanArray(W * H)
        for (i in px.indices) { val p = px[i]; val b = isBlue(p); val o = isOrange(p); blue[i] = b; orange[i] = o; boardish[i] = b || o || isCellBg(p) }

        // --- board rect: rows/cols where >=60% of pixels are board-ish ---
        val rowCnt = IntArray(H); for (y in 0 until H) { var n = 0; val off = y * W; for (x in 0 until W) if (boardish[off + x]) n++; rowCnt[y] = n }
        val rows = (0 until H).filter { rowCnt[it] > 0.6 * W }
        if (rows.isEmpty()) throw ParseException("board rows not found")
        val gapPx = maxOf(6, W / 30)
        val rc = clusters(rows, gapPx).maxByOrNull { it[1] - it[0] }!!
        val by0 = rc[0]; val by1 = rc[1]
        val colCnt = IntArray(W); for (x in 0 until W) { var n = 0; for (y in by0..by1) if (boardish[y * W + x]) n++; colCnt[x] = n }
        val cols = (0 until W).filter { colCnt[it] > 0.6 * (by1 - by0) }
        if (cols.isEmpty()) throw ParseException("board cols not found")
        val cc = clusters(cols, gapPx).maxByOrNull { it[1] - it[0] }!!
        val bx0 = cc[0]; val bx1 = cc[1]
        val pitch = ((bx1 - bx0 + 1) / 9f + (by1 - by0 + 1) / 9f) / 2f
        if (abs((bx1 - bx0) - (by1 - by0)) > pitch * 0.8f) throw ParseException("board not square: ${bx1 - bx0} x ${by1 - by0}")

        // --- cells ---
        val board = IntArray(N * N); val bonus = IntArray(N * N)
        val k = maxOf(2, (pitch * 0.3f).toInt())
        for (r in 0 until N) for (c in 0 until N) {
            val cy = (by0 + (r + 0.5f) * pitch).toInt(); val cx = (bx0 + (c + 0.5f) * pitch).toInt()
            var nb = 0; var no = 0; var ng = 0; var tot = 0
            for (y in cy - k until cy + k) for (x in cx - k until cx + k) {
                if (y < 0 || y >= H || x < 0 || x >= W) continue
                val i = y * W + x; tot++
                if (blue[i]) nb++ else if (orange[i]) no++ else if (isGrayText(px[i])) ng++
            }
            val i = r * N + c
            when {
                no > 0.3 * tot -> board[i] = 2
                nb > 0.3 * tot -> board[i] = 1
                ng > 0.02 * tot -> bonus[i] = 50 // value unknown from pixels; presence is what matters for planning
            }
        }

        // --- tray: components of blue/orange below the board (classifier relative to page bg so dimmed pieces still count) ---
        val ty0 = minOf(H - 1, (by1 + pitch * 2.9f).toInt())
        val trayMask = BooleanArray(W * H); val trayOrange = BooleanArray(W * H)
        for (y in ty0 until H) for (x in 0 until W) {
            val i = y * W + x; val p = px[i]; val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val tb = b - r > 60 && b > 130 && g > 70 && b > g + 20
            val to = r - b > 60 && r > 120
            trayMask[i] = tb || to; trayOrange[i] = to
        }
        val lab = IntArray(W * H)
        class Comp(var y0: Int, var y1: Int, var x0: Int, var x1: Int, var n: Int)
        val comps = ArrayList<Comp>()
        val stack = IntArray(W * (H - ty0) + 1); var labN = 0
        for (y in ty0 until H) for (x in 0 until W) {
            val s = y * W + x
            if (trayMask[s] && lab[s] == 0) {
                labN++; var sp = 0; stack[sp++] = s; lab[s] = labN
                val comp = Comp(y, y, x, x, 0)
                while (sp > 0) {
                    val j = stack[--sp]; comp.n++
                    val jy = j / W; val jx = j % W
                    if (jy < comp.y0) comp.y0 = jy; if (jy > comp.y1) comp.y1 = jy; if (jx < comp.x0) comp.x0 = jx; if (jx > comp.x1) comp.x1 = jx
                    if (jy > ty0) { val n2 = j - W; if (trayMask[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                    if (jy < H - 1) { val n2 = j + W; if (trayMask[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                    if (jx > 0) { val n2 = j - 1; if (trayMask[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                    if (jx < W - 1) { val n2 = j + 1; if (trayMask[n2] && lab[n2] == 0) { lab[n2] = labN; stack[sp++] = n2 } }
                }
                comps.add(comp)
            }
        }
        val minArea = (pitch * pitch * 0.06f).toInt().coerceAtLeast(20)
        val good = comps.filter { it.n > minArea && (it.x1 - it.x0) < W * 0.4 }
        val slots = listOf(ArrayList<Comp>(), ArrayList<Comp>(), ArrayList<Comp>())
        for (cp in good) slots[minOf(2, (((cp.x0 + cp.x1) / 2f) / (W / 3f)).toInt())].add(cp)
        val tray = ArrayList<TrayPiece?>()
        for (sl in slots) {
            if (sl.isEmpty()) { tray.add(null); continue }
            // tray cube ≈ 45.5% of a board cell; refine with component dimensions close to that prior
            val prior = pitch * 0.455f
            val dims = sl.flatMap { listOf(it.x1 - it.x0 + 1, it.y1 - it.y0 + 1) }.filter { abs(it - prior) < prior * 0.25f }
            val cube = if (dims.isNotEmpty()) dims.average().toFloat() else prior
            val mp = cube * 1.13f
            val minx = sl.minOf { it.x0 }; val miny = sl.minOf { it.y0 }; val maxx = sl.maxOf { it.x1 }; val maxy = sl.maxOf { it.y1 }
            val cells = HashMap<Pair<Int, Int>, Int>()
            for (cp in sl) {
                val ny = maxOf(1, ((cp.y1 - cp.y0 + 1 + mp - cube) / mp).roundToInt()); val nx = maxOf(1, ((cp.x1 - cp.x0 + 1 + mp - cube) / mp).roundToInt())
                for (yy in 0 until ny) for (xx in 0 until nx) {
                    val cy = (cp.y0 + (yy + 0.5f) * (cp.y1 - cp.y0 + 1) / ny).toInt(); val cx = (cp.x0 + (xx + 0.5f) * (cp.x1 - cp.x0 + 1) / nx).toInt()
                    if (cy >= H || cx >= W) continue
                    val i = cy * W + cx
                    if (!trayMask[i]) continue
                    val rr = ((cy - miny - mp / 2) / mp).roundToInt(); val c2 = ((cx - minx - mp / 2) / mp).roundToInt()
                    var o = 0; for (y in cy - 1..cy + 1) for (x in cx - 1..cx + 1) if (y in 0 until H && x in 0 until W && trayOrange[y * W + x]) o++
                    cells[Pair(rr, c2)] = if (o >= 4) 2 else 1
                }
            }
            if (cells.isEmpty()) { tray.add(null); continue }
            val piece = Piece(cells.map { Cell(it.key.first, it.key.second, it.value) })
            tray.add(TrayPiece(piece, (minx + maxx) / 2f, (miny + maxy) / 2f, cube, minx, miny, maxx, maxy))
        }
        return Screen(board, bonus, bx0, by0, bx1, by1, pitch, tray, W, H)
    }
}

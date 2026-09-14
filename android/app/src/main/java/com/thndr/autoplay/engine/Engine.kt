package com.thndr.autoplay.engine

/**
 * THNDR block game rules. Board 9x9, values: 0 empty, 1 blue, 2 orange (multiplier cube).
 * Full rows, full columns AND full 3x3 boxes clear.
 */
const val N = 9

data class Cell(val r: Int, val c: Int, val v: Int)

class Piece(cells: List<Cell>, val yv: Int = 1) {
    val cells: List<Cell>
    val h: Int
    val w: Int
    init {
        val minR = cells.minOf { it.r }; val minC = cells.minOf { it.c }
        this.cells = cells.map { Cell(it.r - minR, it.c - minC, it.v) }.sortedWith(compareBy({ it.r }, { it.c }))
        h = this.cells.maxOf { it.r } + 1
        w = this.cells.maxOf { it.c } + 1
    }
    val size get() = cells.size
    override fun toString(): String {
        val sb = StringBuilder()
        for (r in 0 until h) { for (c in 0 until w) { val v = cells.find { it.r == r && it.c == c }?.v ?: 0; sb.append(when (v) { 1 -> '#'; 2 -> 'O'; else -> '.' }) }; sb.append('\n') }
        return sb.toString()
    }
}

class PlaceResult(
    val board: IntArray, val bonus: IntArray,
    val rows: List<Int>, val cols: List<Int>, val boxes: List<Int>,
    val orangeCleared: Int, val yvGain: Int, val bonusHit: Int,
    val cells: IntArray, val clearedIdx: IntArray
) { val lines get() = rows.size + cols.size + boxes.size }

data class Score(val points: Int, val streak: Int, val mult: Int, val lines: Int)

object Engine {
    fun idx(r: Int, c: Int) = r * N + c
    fun emptyBoard() = IntArray(N * N)

    fun canPlace(board: IntArray, p: Piece, r0: Int, c0: Int): Boolean {
        if (r0 < 0 || c0 < 0 || r0 + p.h > N || c0 + p.w > N) return false
        for (c in p.cells) if (board[idx(r0 + c.r, c0 + c.c)] != 0) return false
        return true
    }
    fun anyPlacement(board: IntArray, p: Piece): Boolean {
        for (r in 0..N - p.h) for (c in 0..N - p.w) if (canPlace(board, p, r, c)) return true
        return false
    }
    fun allPlacements(board: IntArray, p: Piece): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (r in 0..N - p.h) for (c in 0..N - p.w) if (canPlace(board, p, r, c)) out.add(intArrayOf(r, c))
        return out
    }
    fun countPlacements(board: IntArray, p: Piece): Int {
        var n = 0
        for (r in 0..N - p.h) for (c in 0..N - p.w) if (canPlace(board, p, r, c)) n++
        return n
    }

    fun place(board: IntArray, bonus: IntArray, p: Piece, r0: Int, c0: Int): PlaceResult {
        val nb = board.copyOf(); val nbonus = bonus.copyOf()
        val cells = IntArray(p.cells.size)
        // REAL RULE (verified on screenshots): a bonus cell pays its value when a cube is placed ON it, then vanishes.
        var bonusHit = 0
        p.cells.forEachIndexed { k, c -> val i = idx(r0 + c.r, c0 + c.c); nb[i] = c.v; cells[k] = i; if (nbonus[i] != 0) { bonusHit += nbonus[i]; nbonus[i] = 0 } }
        val rows = ArrayList<Int>(); val cols = ArrayList<Int>(); val boxes = ArrayList<Int>()
        for (r in 0 until N) { var full = true; for (c in 0 until N) if (nb[idx(r, c)] == 0) { full = false; break }; if (full) rows.add(r) }
        for (c in 0 until N) { var full = true; for (r in 0 until N) if (nb[idx(r, c)] == 0) { full = false; break }; if (full) cols.add(c) }
        for (b in 0 until 9) {
            val br = (b / 3) * 3; val bc = (b % 3) * 3; var full = true
            loop@ for (r in br until br + 3) for (c in bc until bc + 3) if (nb[idx(r, c)] == 0) { full = false; break@loop }
            if (full) boxes.add(b)
        }
        val cleared = HashSet<Int>()
        for (r in rows) for (c in 0 until N) cleared.add(idx(r, c))
        for (c in cols) for (r in 0 until N) cleared.add(idx(r, c))
        for (b in boxes) { val br = (b / 3) * 3; val bc = (b % 3) * 3; for (r in br until br + 3) for (c in bc until bc + 3) cleared.add(idx(r, c)) }
        var orange = 0
        for (i in cleared) { if (nb[i] == 2) orange++; nb[i] = 0 }
        return PlaceResult(nb, nbonus, rows, cols, boxes, orange, orange * p.yv, bonusHit, cells, cleared.toIntArray())
    }

    /** REAL THNDR scoring (from 26 screenshots): (cubes + 20·lines + bonusCovered) × (mult + orangesCleared). */
    fun score(res: PlaceResult, streak0: Int, mult0: Int): Score {
        val nm = mult0 + res.orangeCleared
        val pts = (res.cells.size + 20 * res.lines + res.bonusHit) * nm
        return Score(pts, if (res.lines > 0) streak0 + 1 else 0, nm, res.lines)
    }

    fun shape(vararg rc: Pair<Int, Int>): Piece = Piece(rc.map { Cell(it.first, it.second, 1) })
}

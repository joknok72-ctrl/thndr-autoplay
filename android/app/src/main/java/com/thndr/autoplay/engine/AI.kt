package com.thndr.autoplay.engine

/**
 * Strongest-move planner: all 6 orderings x beam search x board-quality heuristic.
 * Ported from the web version (weights tuned by self-play: 10/10 survival over 25 levels).
 */
data class Move(val slot: Int, val r: Int, val c: Int, val points: Int, val lines: Int)
data class Plan(val moves: List<Move>, val total: Int, val gameOver: Boolean)

object AI {
    private val PROBES: List<Pair<Piece, Double>> = listOf(
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1, 2 to 2) to 3.5,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4) to 2.0,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0) to 2.0,
        Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1) to 1.5,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2) to 1.5,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1) to 1.0,
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3) to 1.0,
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0) to 1.0,
        Engine.shape(0 to 0, 1 to 0, 1 to 1) to 0.6,
        Engine.shape(0 to 0, 0 to 1, 0 to 2) to 0.6,
        Engine.shape(0 to 0, 1 to 0, 2 to 0) to 0.6,
        Engine.shape(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1) to 1.0,
    )
    // weights
    private const val W_EMPTY = 1.0; private const val W_HOLES = 5.0; private const val W_TRANS = 0.9; private const val W_NEAR = 1.6
    private const val W_EDGE = 0.25; private const val W_FIT = 6.0; private const val W_ISL = 2.5; private const val W_SQ3 = 1.5
    private const val W_DEAD = 15.0; private const val W_PTS = 0.5; private const val W_MULT = 45.0

    data class Cfg(val beam: Int, val probes: Int)
    val LEVELS = mapOf(1 to Cfg(6, 6), 2 to Cfg(14, 9), 3 to Cfg(32, 9))

    fun evaluate(board: IntArray, bonus: IntArray, cfg: Cfg): Double {
        var empty = 0; var holes = 0; var trans = 0; var nearFull = 0; var edge = 0
        val rf = IntArray(N); val cf = IntArray(N)
        for (r in 0 until N) for (c in 0 until N) { if (board[Engine.idx(r, c)] != 0) { rf[r]++; cf[c]++ } else empty++ }
        for (r in 0 until N) for (c in 0 until N) {
            val i = Engine.idx(r, c); val v = board[i] != 0
            if (c < N - 1 && v != (board[Engine.idx(r, c + 1)] != 0)) trans++
            if (r < N - 1 && v != (board[Engine.idx(r + 1, c)] != 0)) trans++
            if (!v) {
                val up = r == 0 || board[Engine.idx(r - 1, c)] != 0; val dn = r == N - 1 || board[Engine.idx(r + 1, c)] != 0
                val lf = c == 0 || board[Engine.idx(r, c - 1)] != 0; val rt = c == N - 1 || board[Engine.idx(r, c + 1)] != 0
                val n = (if (up) 1 else 0) + (if (dn) 1 else 0) + (if (lf) 1 else 0) + (if (rt) 1 else 0)
                if (n == 4) holes += 3 else if (n == 3) holes += 1
            } else if (r == 0 || r == N - 1 || c == 0 || c == N - 1) edge++
        }
        for (k in 0 until N) {
            if (rf[k] >= 7) nearFull += rf[k] - 6
            if (cf[k] >= 7) nearFull += cf[k] - 6
            val br = (k / 3) * 3; val bc = (k % 3) * 3; var bf = 0
            for (r in br until br + 3) for (c in bc until bc + 3) if (board[Engine.idx(r, c)] != 0) bf++
            if (bf >= 7) nearFull += bf - 6
        }
        var fit = 0.0; var dead = 0
        for (k in 0 until minOf(cfg.probes, PROBES.size)) {
            val (p, w) = PROBES[k]; val n = Engine.countPlacements(board, p)
            fit += minOf(n, 12) * w / 12.0
            if (n == 0) { fit -= w * 2; dead++ }
        }
        var sq3 = 0
        for (r in 0..6) for (c in 0..6) { var ok = true; loop@ for (a in 0 until 3) for (b in 0 until 3) if (board[Engine.idx(r + a, c + b)] != 0) { ok = false; break@loop }; if (ok) sq3++ }
        // small islands
        var islands = 0; val seen = BooleanArray(N * N); val st = ArrayDeque<Int>()
        for (i in 0 until N * N) if (board[i] != 0 && !seen[i]) {
            var size = 0; st.clear(); st.add(i); seen[i] = true
            while (st.isNotEmpty()) {
                val j = st.removeLast(); size++; val r = j / N; val c = j % N
                if (r > 0 && board[j - N] != 0 && !seen[j - N]) { seen[j - N] = true; st.add(j - N) }
                if (r < N - 1 && board[j + N] != 0 && !seen[j + N]) { seen[j + N] = true; st.add(j + N) }
                if (c > 0 && board[j - 1] != 0 && !seen[j - 1]) { seen[j - 1] = true; st.add(j - 1) }
                if (c < N - 1 && board[j + 1] != 0 && !seen[j + 1]) { seen[j + 1] = true; st.add(j + 1) }
            }
            if (size <= 2) islands++
        }
        var bonusPot = 0.0
        for (i in 0 until N * N) if (bonus[i] != 0) { val r = i / N; val c = i % N; bonusPot += bonus[i] * (maxOf(rf[r], cf[c]) / N.toDouble()) * 0.02 }
        return empty * W_EMPTY - holes * W_HOLES - trans * W_TRANS + nearFull * W_NEAR + edge * W_EDGE + fit * W_FIT - islands * W_ISL + bonusPot + sq3 * W_SQ3 - dead * W_DEAD
    }

    private class Node(val board: IntArray, val bonus: IntArray, val streak: Int, val mult: Int, val pts: Int, val score: Double, val moves: List<Move>)

    private fun perms(a: List<Int>): List<List<Int>> = if (a.size <= 1) listOf(a) else a.flatMap { x -> perms(a - x).map { listOf(x) + it } }

    fun plan(board: IntArray, bonus: IntArray, pieces: List<Piece?>, streak: Int, mult: Int, level: Int = 3): Plan {
        val cfg = LEVELS[level] ?: LEVELS[3]!!
        val slots = pieces.indices.filter { pieces[it] != null }
        if (slots.isEmpty()) return Plan(emptyList(), 0, false)
        var best: Node? = null
        for (order in perms(slots)) {
            var beam = listOf(Node(board, bonus, streak, mult, 0, 0.0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]!!; val next = ArrayList<Node>()
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val sc = Engine.score(res, node.streak, node.mult)
                    val pts = node.pts + sc.points
                    val heur = evaluate(res.board, res.bonus, cfg) + (sc.mult - node.mult) * W_MULT
                    next.add(Node(res.board, res.bonus, sc.streak, sc.mult, pts, pts * W_PTS + heur, node.moves + Move(slot, rc[0], rc[1], sc.points, sc.lines)))
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                beam = next.take(if (step == order.size - 1) 1 else cfg.beam)
            }
            if (beam.isNotEmpty() && (best == null || beam[0].score > best!!.score)) best = beam[0]
        }
        val b = best ?: return Plan(emptyList(), 0, true)
        return Plan(b.moves, b.pts, false)
    }
}

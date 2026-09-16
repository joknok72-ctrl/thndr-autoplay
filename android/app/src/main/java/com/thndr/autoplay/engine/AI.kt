package com.thndr.autoplay.engine

/**
 * SCORE-MAX planner. Scoring rules reverse-engineered from 26 consecutive real screenshots (one per placed piece):
 *
 *   points(move) = (cubes + 20 × linesCleared + bonusCovered) × multAfter
 *   multAfter    = mult + orangeCubesCleared         ← permanent, multiplies EVERYTHING that follows
 *   bonus cells (50/150/…) pay when COVERED by a piece and then vanish; new ones spawn (max 3); values grow with level
 *   a round of 3 pieces = 1 level; 25 levels = 75 pieces; exactly one piece per round carries an orange cube
 *
 * Strategy: maximise the total over the remaining game, not just this move:
 *   immediate points + (multiplier gained × remaining moves × avg base) + oranges parked in nearly complete lines
 *   + board quality/survival weighted by how much is still to be earned
 *   + END BONUS: 1000 per cube left on the board when level 25 is completed (so the last moves FILL the board).
 * Weights tuned by self-play simulation under the real rules.
 */
data class Move(val slot: Int, val r: Int, val c: Int, val points: Int, val lines: Int)
data class Plan(val moves: List<Move>, val total: Int, val gameOver: Boolean, val finalMult: Int = 1)

object AI {
    // probes = pieces that actually occur in the real game (no 3x3 / 5-long bars — they are never dealt)
    private val PROBES: List<Pair<Piece, Double>> = listOf(
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1) to 2.0,   // T5a
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 2, 2 to 2) to 1.5,   // Lbig3
        Engine.shape(0 to 0, 0 to 2, 1 to 0, 1 to 1, 1 to 2) to 1.5,   // U1
        Engine.shape(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1) to 1.5,   // plus
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 1 to 1) to 1.0,           // T4a
        Engine.shape(0 to 1, 1 to 1, 2 to 0, 2 to 1) to 1.0,           // J4a
        Engine.shape(0 to 0, 0 to 1, 0 to 2, 0 to 3) to 1.0,           // i4
        Engine.shape(0 to 1, 0 to 2, 1 to 0, 1 to 1) to 1.0,           // S4a
        Engine.shape(0 to 0, 0 to 1, 1 to 0, 1 to 1) to 1.0,           // sq2
        Engine.shape(0 to 0, 1 to 0, 2 to 0, 3 to 0) to 1.0,           // v4
        Engine.shape(0 to 0, 1 to 0, 1 to 1) to 0.6,                   // l3a
        Engine.shape(0 to 0, 0 to 1, 0 to 2) to 0.6,                   // i3
    )
    // board-quality weights
    private const val W_EMPTY = 1.0; private const val W_HOLES = 5.0; private const val W_TRANS = 0.9; private const val W_NEAR = 1.6
    private const val W_EDGE = 0.25; private const val W_FIT = 6.0; private const val W_ISL = 2.5; private const val W_SQ3 = 1.5; private const val W_DEAD = 15.0
    // score-max weights (tuned by simulation)
    @JvmField var K_MULT = 18.0        // value of +1 multiplier per remaining move
    @JvmField var W_SURV = 4.0         // survival weight (re-tuned on the REAL piece distribution: 0 deaths / 20 games)
    @JvmField var W_ORANGE = 0.3       // oranges parked in near-complete lines
    @JvmField var W_BONUS_KEEP = 0.4   // keep uncovered bonus cells coverable (legacy, used when W_FARM = 0)
    @JvmField var W_FARM = 2.0         // bonus farming weight (measured: 77.8K → 126K–140K on the realistic sim, 0 deaths)
    @JvmField var FARM_RATE = 0.33     // expected tier steps per round while 3 cells are farmed
    @JvmField var FARM_CUBES0 = 25     // farming value starts fading at this many cubes on the board …
    @JvmField var FARM_CUBES1 = 45     // … and is down to FARM_MIN here (measured: removes the mid-game deaths)
    @JvmField var FARM_MIN = 0.1
    @JvmField var FARM_HI_TIER = 7       // tier index of 2K in TIER
    @JvmField var FARM_HI_SCALE = 0.0    // crowding-fade floor for high tiers (0 = same as low tiers)
    @JvmField var CROWD_W = 1.0          // crowding penalty weight (0 = off)
    @JvmField var CROWD0 = 30
    @JvmField var RUN5_W = 0.4           // long-piece room (I5/V5) penalty
    @JvmField var FARM_MODEL = 0         // 1 = optimal cash-out model
    @JvmField var FARM_SURV = 0.985
    @JvmField var FARM_K = 0.3
    @JvmField var TRAY_M = 40          // NEXT-TRAY SAFETY: sample M random real trays; 0 = off
    @JvmField var TRAY_K = 48          // re-rank the top-K plans by P(next tray cannot be placed)
    @JvmField var TRAY_K_OPEN = 24     // … plus the K most open boards (fewest cubes)
    @JvmField var ROLL_CUT = 6000.0   // successive halving threshold (per future) in the end-game rollouts
    @JvmField var ROLL_DEATH = 50000.0     // extra penalty for a dead future inside the end-game rollouts
    @JvmField var ROLL_M_SCALE = 2.0   // futures in the last rounds = ROLL_M × ROLL_ROUNDS/roundsLeft × scale
    @JvmField var TRAY2_M = 12         // look TWO trays ahead (0 = off)
    @JvmField var TRAY2_K = 8
    @JvmField var TRAY2_W = 0.7
    @JvmField var LAST_TRAY_M = 60     // last-round safety: sample the final tray directly
    @JvmField var LAST_TRAY_LOSS = 60000.0
    @JvmField var INNER_SAFE_K = 6     // inner rollout planner: on the 2nd-to-last round keep K candidates and pick the one the final tray fits
    @JvmField var INNER_SAFE_M = 6
    @JvmField var TRAY_CUBES = 24      // only when the board has at least this many cubes (empty boards are always safe)
    @JvmField var DEATH = 60000.0      // cost of dying (base) …
    @JvmField var DEATH_REM = 1500.0   // … plus per remaining move (farmed bonuses + 1000/cube end bonus forfeited)
    private val TIER = intArrayOf(50, 150, 300, 500, 750, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7000, 7500, 8000, 8500, 9000, 9500, 10000)  // REAL (screens): +500 per level after 2K
    @JvmField var W_COVER = 1.0        // real-piece survivability: penalty ∝ P(next piece has no place) × stake
    @JvmField var W_TIGHT = 1.0
    @JvmField var STAKE = 24.0         // ≈ points per remaining move per multiplier unit
    @JvmField var W_CLEAN = 0.0        // clean-board style: reward per empty cell before the fill phase (player's strategy)
    @JvmField var END_CUBE = 1000.0    // REAL RULE: every cube still on the board after level 25 pays 1000 (only if the game is completed)
    @JvmField var END_FADE = 9         // the end-bonus fades in over the last N moves
    @JvmField var END_BEAM = 64        // wider beam in the last END_BEAM_REM moves (deeper end-game search)
    @JvmField var END_BEAM_REM = 12
    @JvmField var ROLL_ROUNDS = 3      // end-game rollouts in the last N rounds
    @JvmField var ROLL_K = 5           // candidates re-ranked by rollout
    @JvmField var ROLL_K_FILL = 3      // + best board-filling candidates
    @JvmField var ROLL_K_PTS = 2       // + best raw-points candidates
    @JvmField var ROLL_K_OPEN = 6      // + the most open boards in the end-game rollouts (safety)
    @JvmField var ROLL_M = 6           // random futures per candidate
    @JvmField var ROLL_LEVEL = 1       // planner level used inside rollouts (fast)

    data class Cfg(val beam: Int, val probes: Int)
    val LEVELS = mapOf(1 to Cfg(6, 6), 2 to Cfg(14, 9), 3 to Cfg(32, 12))

    /** Real THNDR scoring. */
    fun realPoints(res: PlaceResult, mult: Int): Pair<Int, Int> {
        val nm = mult + res.orangeCleared
        return Pair((res.cells.size + 20 * res.lines + res.bonusHit) * nm, nm)
    }

    private class BQ(val q: Double, val rf: IntArray, val cf: IntArray, val bf: IntArray, val dead: Int, val cover: Double, val tight: Double, val empty: Int, val run5v: Int = 0, val run5h: Int = 0)

    // MEMO: inside rollouts the same board is evaluated thousands of times → bounded, thread-safe cache keyed by occupancy
    private val BQ_CACHE = java.util.concurrent.ConcurrentHashMap<String, BQ>()
    private fun boardKey(board: IntArray, cfg: Cfg): String { val sb = StringBuilder(N * N + 4); for (v in board) sb.append(if (v != 0) '1' else '0'); sb.append('|').append(cfg.probes); return sb.toString() }
    private fun boardQuality(board: IntArray, cfg: Cfg): BQ {
        val key = boardKey(board, cfg)
        BQ_CACHE[key]?.let { return it }
        val out = boardQuality0(board, cfg)
        if (BQ_CACHE.size >= 60000) BQ_CACHE.clear()
        BQ_CACHE[key] = out; return out
    }
    private fun boardQuality0(board: IntArray, cfg: Cfg): BQ {
        var empty = 0; var holes = 0; var trans = 0; var nearFull = 0; var edge = 0
        val rf = IntArray(N); val cf = IntArray(N); val bf = IntArray(9)
        for (r in 0 until N) for (c in 0 until N) { if (board[Engine.idx(r, c)] != 0) { rf[r]++; cf[c]++; bf[(r / 3) * 3 + c / 3]++ } else empty++ }
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
            if (bf[k] >= 7) nearFull += bf[k] - 6
        }
        // REAL-PIECE SURVIVABILITY: weighted fraction of the pieces the game actually deals that still have a place
        var cover = 0.0; var tight = 0.0
        if (W_COVER > 0) {
            for (lp in LIBP) { val n = Engine.countPlacements(board, lp.first, 3); if (n > 0) cover += lp.second; if (n < 3) tight += lp.second * (3 - n) / 3.0 }
            cover /= LIBW; tight /= LIBW
        }
        var fit = 0.0; var dead = 0
        for (k in 0 until minOf(cfg.probes, PROBES.size)) {
            val (p, w) = PROBES[k]; val n = Engine.countPlacements(board, p)
            fit += minOf(n, 12) * w / 12.0
            if (n == 0) { fit -= w * 2; dead++ }
        }
        var sq3 = 0
        for (r in 0..6) for (c in 0..6) { var ok = true; loop@ for (a in 0 until 3) for (b in 0 until 3) if (board[Engine.idx(r + a, c + b)] != 0) { ok = false; break@loop }; if (ok) sq3++ }
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
        val q = empty * W_EMPTY - holes * W_HOLES - trans * W_TRANS + nearFull * W_NEAR + edge * W_EDGE + fit * W_FIT - islands * W_ISL + sq3 * W_SQ3 - dead * W_DEAD
        // LONG-PIECE ROOM: columns / rows that still hold a straight run of 5 empty cells (I5 / V5 ≈ 4% of deals each)
        var run5v = 0; var run5h = 0
        for (k in 0 until N) { var rv = 0; var rh = 0; var okv = false; var okh = false; for (j in 0 until N) { rv = if (board[Engine.idx(j, k)] != 0) 0 else rv + 1; if (rv >= 5) okv = true; rh = if (board[Engine.idx(k, j)] != 0) 0 else rh + 1; if (rh >= 5) okh = true }; if (okv) run5v++; if (okh) run5h++ }
        return BQ(q, rf, cf, bf, dead, cover, tight, empty, run5v, run5h)
    }

    private class Node(val board: IntArray, val bonus: IntArray, val mult: Int, val pts: Int, val moves: List<Move>) { var score = 0.0 }

    /** remaining = moves left in the game AFTER this node. */
    private fun evaluate(node: Node, mult0: Int, remaining: Int, cfg: Cfg): Double {
        val bq = boardQuality(node.board, cfg)
        val rem = maxOf(0, remaining)
        val multGain = (node.mult - mult0) * rem * K_MULT
        var orangePot = 0.0
        for (i in 0 until N * N) if (node.board[i] == 2) {
            val r = i / N; val c = i % N; val b = (r / 3) * 3 + c / 3
            val prog = maxOf(bq.rf[r], bq.cf[c], bq.bf[b]) / 9.0
            orangePot += rem * K_MULT * W_ORANGE * prog * prog
        }
        // BONUS CELLS — REAL RULE (measured on 6 logged games): while 3 cells sit on the board and none is covered during a
        // round, one of them grows a tier each level (50→150→300→500→1K→2K). "Farming": value uncovered cells by their
        // expected FUTURE value (they grow, and the multiplier grows) as long as enough moves remain to cash them in.
        var bonusPot = 0.0
        var nB = 0; var nCubes = 0; for (i in 0 until N * N) { if (node.bonus[i] != 0 && node.board[i] == 0) nB++; if (node.board[i] != 0) nCubes++ }
        // SAFETY: 3 uncovered bonus cells lock up to 9 lines (their rows/cols/boxes cannot clear). On a crowded board that is how
        // games die — so the farming value fades out with crowding (the planner then cashes a cell in, which frees its lines).
        val farmScale = if (FARM_CUBES1 > FARM_CUBES0) maxOf(FARM_MIN, minOf(1.0, (FARM_CUBES1 - nCubes).toDouble() / (FARM_CUBES1 - FARM_CUBES0))) else 1.0
        for (i in 0 until N * N) if (node.bonus[i] != 0 && node.board[i] == 0) {
            if (W_FARM > 0) {
                var t = TIER.indexOf(node.bonus[i]); if (t < 0) { t = 0; while (t < TIER.size - 1 && TIER[t + 1] <= node.bonus[i]) t++ }
                val roundsLeft = rem / 3.0
                val steps = if (nB >= 3) minOf((TIER.size - 1 - t).toDouble(), roundsLeft * FARM_RATE) else 0.0
                val fut = t + steps; val lo = fut.toInt().coerceIn(0, TIER.size - 1); val hi = minOf(TIER.size - 1, lo + 1)
                val fv = TIER[lo] + (TIER[hi] - TIER[lo]) * (fut - lo)
                val multFut = node.mult + minOf(roundsLeft, 25.0) * 0.8
                // high tiers: the crowding fade applies less — a 2K→3K→5K cell is worth keeping even on a busy board
                val fsc = if (t >= FARM_HI_TIER) maxOf(farmScale, FARM_HI_SCALE) else farmScale
                if (FARM_MODEL == 1) {
                    // OPTIMAL CASH-OUT MODEL: value = max over future round k of TIER[t + k×growRate] × (mult + 0.8k) × surv^k
                    val g = if (nB >= 3) FARM_RATE else 0.0; var bestV = 0.0; var k = 0
                    while (k < roundsLeft) {
                        val ft = minOf((TIER.size - 1).toDouble(), t + k * g); val lo2 = ft.toInt().coerceIn(0, TIER.size - 1); val hi2 = minOf(TIER.size - 1, lo2 + 1)
                        val v = (TIER[lo2] + (TIER[hi2] - TIER[lo2]) * (ft - lo2)) * (node.mult + k * 0.8) * Math.pow(FARM_SURV, k.toDouble())
                        if (v > bestV) bestV = v; k++
                    }
                    if (rem >= 3) bonusPot += bestV * W_FARM * fsc * FARM_K
                } else if (rem >= 3) bonusPot += fv * multFut * W_FARM * fsc * 0.3
            } else if (rem > 3) bonusPot += node.bonus[i] * node.mult * W_BONUS_KEEP * 0.3
        }
        val survW = W_SURV * minOf(1.0, rem / 15.0) * (1 + node.mult * 0.15) * 4
        // END BONUS: in the last moves keep as many cubes as possible on the board (1000 each at the end) —
        // but never at the price of dying: the dead-board penalty is 6× stronger in that phase.
        var endVal = 0.0
        if (rem < END_FADE) { var cubes = 0; for (i in 0 until N * N) if (node.board[i] != 0) cubes++; val w = 1.0 - rem.toDouble() / END_FADE; endVal = cubes * END_CUBE * w * w }
        val deadPen = if (bq.dead > 0 && rem > 0) 400.0 * (1 + node.mult * 0.2) * (if (rem < END_FADE) 6 else 1) else 0.0
        // DYING = losing everything still to come (remaining × mult × STAKE) — (1-cover) ≈ chance the next piece has no place
        // CLEAN-BOARD PHASE (player's strategy): before the fill phase, reward an empty board (flexibility + every clear pays 20×mult)
        val cleanVal = if (rem >= END_FADE) bq.empty * W_CLEAN * (1 + node.mult * 0.15) else 0.0
        val stake = if (rem > 0) rem * node.mult * STAKE else 0.0
        val surviv = if (rem > 0 && W_COVER > 0) -(1 - bq.cover) * stake * W_COVER - bq.tight * stake * W_TIGHT else 0.0
        // CROWDING PENALTY: P(a 3-piece tray cannot be placed) ≈ 0 below 30 cubes, ~5% at 38, ~35% at 45–50 (measured on the
        // real deal distribution). Outside the fill phase, charge that probability × what dying would forfeit.
        var crowd = 0.0
        if (rem >= END_FADE && CROWD_W > 0) { var cubes = 0; for (i in 0 until N * N) if (node.board[i] != 0) cubes++; val x = maxOf(0, cubes - CROWD0) / 15.0; crowd = -x * x * CROWD_W * (stake + DEATH * 0.3) }
        val run5 = if (rem >= END_FADE && RUN5_W > 0) -((2 - minOf(2, bq.run5v)) + (2 - minOf(2, bq.run5h))) * RUN5_W * (stake + DEATH * 0.3) * 0.05 else 0.0
        return node.pts + multGain + orangePot + bonusPot + bq.q * survW + endVal - deadPen + surviv + cleanVal + crowd + run5
    }

    // ---- piece library with the REAL deal distribution (96 pieces observed across two full games; big 3x3 / 5-bars never appear) ----
    private val LIB: List<Triple<String, Double, IntArray>> = listOf(
        Triple("dot", 16.0, intArrayOf(0,0)),
        Triple("i2", 18.0, intArrayOf(0,0, 0,1)),
        Triple("v2", 20.0, intArrayOf(0,0, 1,0)),
        Triple("i3", 12.0, intArrayOf(0,0, 0,1, 0,2)),
        Triple("v3", 24.0, intArrayOf(0,0, 1,0, 2,0)),
        Triple("i4", 13.0, intArrayOf(0,0, 0,1, 0,2, 0,3)),
        Triple("v4", 16.0, intArrayOf(0,0, 1,0, 2,0, 3,0)),
        Triple("i5", 7.0, intArrayOf(0,0, 0,1, 0,2, 0,3, 0,4)),
        Triple("v5", 19.0, intArrayOf(0,0, 1,0, 2,0, 3,0, 4,0)),
        Triple("sq2", 15.0, intArrayOf(0,0, 0,1, 1,0, 1,1)),
        Triple("l3a", 10.0, intArrayOf(0,0, 1,0, 1,1)),
        Triple("l3b", 17.0, intArrayOf(0,0, 0,1, 1,0)),
        Triple("l3c", 9.0, intArrayOf(0,0, 0,1, 1,1)),
        Triple("l3d", 16.0, intArrayOf(0,1, 1,0, 1,1)),
        Triple("L4a", 1.0, intArrayOf(0,0, 1,0, 2,0, 2,1)),
        Triple("L4b", 13.0, intArrayOf(0,0, 0,1, 1,0, 2,0)),
        Triple("L4c", 1.0, intArrayOf(0,0, 0,1, 0,2, 1,0)),
        Triple("L4d", 1.0, intArrayOf(0,2, 1,0, 1,1, 1,2)),
        Triple("J4a", 15.0, intArrayOf(0,1, 1,1, 2,0, 2,1)),
        Triple("J4b", 16.0, intArrayOf(0,0, 1,0, 1,1, 1,2)),
        Triple("J4c", 1.0, intArrayOf(0,0, 0,1, 1,1, 2,1)),
        Triple("J4d", 8.0, intArrayOf(0,0, 0,1, 0,2, 1,2)),
        Triple("T4a", 18.0, intArrayOf(0,0, 0,1, 0,2, 1,1)),
        Triple("T4b", 15.0, intArrayOf(0,1, 1,0, 1,1, 1,2)),
        Triple("T4c", 26.0, intArrayOf(0,0, 1,0, 1,1, 2,0)),
        Triple("T4d", 8.0, intArrayOf(0,1, 1,0, 1,1, 2,1)),
        Triple("S4a", 11.0, intArrayOf(0,1, 0,2, 1,0, 1,1)),
        Triple("S4b", 14.0, intArrayOf(0,0, 1,0, 1,1, 2,1)),
        Triple("Z4a", 15.0, intArrayOf(0,0, 0,1, 1,1, 1,2)),
        Triple("Z4b", 14.0, intArrayOf(0,1, 1,0, 1,1, 2,0)),
        Triple("Lbig1", 12.0, intArrayOf(0,0, 1,0, 2,0, 2,1, 2,2)),
        Triple("Lbig2", 16.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 2,0)),
        Triple("Lbig3", 18.0, intArrayOf(0,0, 0,1, 0,2, 1,2, 2,2)),
        Triple("Lbig4", 27.0, intArrayOf(0,2, 1,2, 2,0, 2,1, 2,2)),
        Triple("plus", 10.0, intArrayOf(0,1, 1,0, 1,1, 1,2, 2,1)),
        Triple("U1", 15.0, intArrayOf(0,0, 0,2, 1,0, 1,1, 1,2)),
        Triple("U2", 13.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,2)),
        Triple("U3", 10.0, intArrayOf(0,0, 0,1, 1,0, 2,0, 2,1)),
        Triple("U4", 15.0, intArrayOf(0,0, 0,1, 1,1, 2,0, 2,1)),
        Triple("d2a", 9.0, intArrayOf(0,1, 1,0)),
        Triple("d2b", 18.0, intArrayOf(0,0, 1,1)),
        Triple("d3a", 10.0, intArrayOf(0,2, 1,1, 2,0)),
        Triple("d3b", 15.0, intArrayOf(0,0, 1,1, 2,2)),
        Triple("stair", 0.0, intArrayOf(0,2, 1,1, 1,2, 2,0, 2,1)),
        Triple("rect23", 0.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,1, 1,2)),
        Triple("rect32", 0.0, intArrayOf(0,0, 0,1, 1,0, 1,1, 2,0, 2,1)),
        Triple("sq3", 0.0, intArrayOf(0,0, 0,1, 0,2, 1,0, 1,1, 1,2, 2,0, 2,1, 2,2)),
        Triple("T5a", 21.0, intArrayOf(0,0, 0,1, 0,2, 1,1, 2,1)),
        Triple("T5b", 16.0, intArrayOf(0,2, 1,0, 1,1, 1,2, 2,2)),
        Triple("T5c", 10.0, intArrayOf(0,0, 1,0, 1,1, 1,2, 2,0)),
        Triple("T5d", 15.0, intArrayOf(0,1, 1,1, 2,0, 2,1, 2,2)),
        Triple("S5a", 1.0, intArrayOf(0,1, 0,2, 1,1, 2,0, 2,1)),
        Triple("S5b", 4.0, intArrayOf(0,0, 1,0, 1,1, 1,2, 2,2)),
        Triple("d4", 8.0, intArrayOf(0,0, 1,1, 2,2, 3,3)),
        Triple("d4b", 6.0, intArrayOf(0,3, 1,2, 2,1, 3,0)),
        Triple("d5", 3.0, intArrayOf(0,0, 1,1, 2,2, 3,3, 4,4)),
        Triple("d5b", 1.0, intArrayOf(0,4, 1,3, 2,2, 3,1, 4,0)),
        Triple("S5c", 2.0, intArrayOf(0,0, 0,1, 1,1, 2,1, 2,2)),
        Triple("S5d", 1.0, intArrayOf(0,2, 1,0, 1,1, 1,2, 2,0)),
    )
    fun randomPiece(rng: java.util.Random): Piece {
        var total = 0.0; for (t in LIB) total += t.second
        var x = rng.nextDouble() * total; var pick = LIB[0]
        for (t in LIB) { x -= t.second; if (x <= 0 && t.second > 0) { pick = t; break } }
        val a = pick.third
        return Piece((0 until a.size / 2).map { Cell(a[it * 2], a[it * 2 + 1], 1) })
    }

    /** Can ALL the pieces of a tray be placed in some order (line clears included)? Exhaustive with early exit. */
    fun trayFeasible(board: IntArray, pieces: List<Piece?>): Boolean {
        val idx = pieces.indices.filter { pieces[it] != null }; if (idx.isEmpty()) return true
        val nob = IntArray(N * N)
        for (i in idx) { val p = pieces[i]!!; for (rc in Engine.allPlacements(board, p)) { val res = Engine.place(board, nob, p, rc[0], rc[1]); val rest = pieces.toMutableList(); rest[i] = null; if (trayFeasible(res.board, rest)) return true } }
        return false
    }
    private fun randomTray(rng: java.util.Random): List<Piece> {
        val ps = (0 until 3).map { randomPiece(rng) }
        val oi = rng.nextInt(3); val op = ps[oi]; val ci = rng.nextInt(op.cells.size)
        return ps.mapIndexed { i, p -> if (i == oi) Piece(p.cells.mapIndexed { j, c -> if (j == ci) Cell(c.r, c.c, 2) else c }) else p }
    }

    private val LIBP: List<Pair<Piece, Double>> = LIB.filter { it.second > 0 }.map { t -> Piece((0 until t.third.size / 2).map { Cell(t.third[it * 2], t.third[it * 2 + 1], 1) }) to t.second }
    private val LIBW: Double = LIBP.sumOf { it.second }.coerceAtLeast(1.0)

    /** Shared thread pool. Its size is re-tuned before every plan from the RAM that is actually free (see [setThreads] / [tuneThreads]). */
    val CORES: Int = maxOf(1, Runtime.getRuntime().availableProcessors())
    @Volatile var threadCount: Int = CORES
    private val POOL: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(CORES, CORES, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()).also { it.allowCoreThreadTimeOut(true) }
    }
    /** Resize the pool to [n] worker threads (1..CORES). */
    fun setThreads(n: Int) {
        val t = n.coerceIn(1, CORES)
        threadCount = t
        try {
            if (t >= POOL.maximumPoolSize) { POOL.maximumPoolSize = t; POOL.corePoolSize = t } else { POOL.corePoolSize = t; POOL.maximumPoolSize = t }
        } catch (_: Throwable) {}
    }
    /**
     * Choose the thread count from the memory that is really free right now:
     *  - [availSys]  = free system RAM in bytes (ActivityManager.MemoryInfo.availMem − threshold), or -1 if unknown
     *  - [totalSys]  = total system RAM in bytes, or -1 if unknown
     * Each planning thread needs roughly 96 MB of system RAM and ~24 MB of Java heap. If the system is mostly free (≥ 45 % of RAM
     * available) we take every core; otherwise we use only what the free RAM affords — never fewer than one thread.
     */
    fun tuneThreads(availSys: Long, totalSys: Long): Int {
        val rt = Runtime.getRuntime()
        val heapFree = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        var n = CORES
        if (availSys >= 0) {
            val mostlyFree = totalSys > 0 && availSys >= totalSys * 0.45
            if (!mostlyFree) n = minOf(n, (availSys / (96L shl 20)).toInt())
        }
        n = minOf(n, (heapFree / (24L shl 20)).toInt())
        setThreads(maxOf(1, n))
        return threadCount
    }
    fun <T, R> parallelMap(items: List<T>, f: (T) -> R): List<R> {
        if (items.size <= 1 || threadCount <= 1) return items.map(f)
        return try {
            val futs = items.map { it -> POOL.submit(java.util.concurrent.Callable { f(it) }) }
            futs.map { it.get() }
        } catch (_: Throwable) { items.map(f) }
    }

    private fun perms(a: List<Int>): List<List<Int>> = if (a.size <= 1) listOf(a) else a.flatMap { x -> perms(a - x).map { listOf(x) + it } }

    /**
     * @param mult   current multiplier (from the "NX" pill)
     * @param gameLevel current level 1..25 (from the "LEVEL n/25" pill)
     * @param deep   deeper end-game search (wider beam in the last moves + rollouts). ON by default — identical to the web site.
     * @param rollout end-game rollouts (last ROLL_ROUNDS rounds): re-rank the best candidates by simulating the
     *                remaining rounds with random pieces — same futures for every candidate.
     */
    fun plan(board: IntArray, bonus: IntArray, pieces: List<Piece?>, mult: Int, gameLevel: Int, level: Int = 3, deep: Boolean = true, rollout: Boolean = true, gameScore: Int = 0): Plan {
        val cfg = LEVELS[level.coerceIn(1, 3)]!!
        val slots = pieces.indices.filter { pieces[it] != null }
        if (slots.isEmpty()) return Plan(emptyList(), 0, false, mult)
        val mult0 = maxOf(1, mult); val lvl = gameLevel.coerceIn(1, 25)
        val banked = maxOf(0, gameScore).toDouble()   // points already scored — ALL of it is lost on death
        val remAfterRound = (25 - lvl) * 3
        val roundsLeft = 25 - lvl
        val rollActive = deep && rollout && ROLL_ROUNDS > 0 && roundsLeft in 0 until ROLL_ROUNDS
        val trayActive = !rollActive && deep && rollout && TRAY_M > 0
        var best: Node? = null
        val cands = ArrayList<Node>()
        // MULTI-CORE: the 6 piece orderings are independent → one task per ordering on all available cores
        val orderResults = parallelMap(perms(slots)) { order ->
            var beam = listOf(Node(board, bonus, mult0, 0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]!!; val next = ArrayList<Node>()
                val remaining = remAfterRound + (order.size - 1 - step)
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val (pts, nm) = realPoints(res, node.mult)
                    val nn = Node(res.board, res.bonus, nm, node.pts + pts, node.moves + Move(slot, rc[0], rc[1], pts, res.lines))
                    nn.score = evaluate(nn, mult0, remaining, cfg)
                    next.add(nn)
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                val keep = if (step == order.size - 1) (if (rollActive) maxOf(ROLL_K, ROLL_K_OPEN, ROLL_K_FILL) else if (trayActive) maxOf(TRAY_K, TRAY_K_OPEN) else 1)
                           else if (deep && remaining <= END_BEAM_REM) maxOf(cfg.beam, END_BEAM) else cfg.beam
                beam = next.take(keep)
            }
            beam
        }
        for (beam in orderResults) if (beam.isNotEmpty()) { cands.addAll(beam); if (best == null || beam[0].score > best.score) best = beam[0] }
        var b = best ?: return Plan(emptyList(), 0, true, mult0)
        if (rollActive && cands.size > 1) {
            cands.sortByDescending { it.score }
            // candidate diversity: heuristic top-K + best "fill" plans (pts + cubes×1000) + best raw-points plans
            val top = ArrayList(cands.take(ROLL_K))
            for (n in cands.sortedByDescending { it.pts + it.board.count { v -> v != 0 } * END_CUBE }) { if (top.size >= ROLL_K + ROLL_K_FILL) break; if (n !in top) top.add(n) }
            for (n in cands.sortedByDescending { it.pts }) { if (top.size >= ROLL_K + ROLL_K_FILL + ROLL_K_PTS) break; if (n !in top) top.add(n) }
            // + the most OPEN boards (fewest cubes): when the next tray may not fit, the safe line is rarely in the top-K
            if (ROLL_K_OPEN > 0) for (n in cands.sortedBy { it.board.count { v -> v != 0 } }) { if (top.size >= ROLL_K + ROLL_K_FILL + ROLL_K_PTS + ROLL_K_OPEN) break; if (n !in top) top.add(n) }
            val rng = java.util.Random(12345L + lvl * 7919L)
            // more futures when fewer rounds remain (same cost): the last round's risk of an unplaceable tray must be sampled well
            val nFut = maxOf(ROLL_M, Math.round(ROLL_M * (ROLL_ROUNDS.toDouble() / maxOf(1, roundsLeft)) * ROLL_M_SCALE).toInt())
            val futures = (0 until nFut).map {
                (0 until roundsLeft).map {
                    val ps = (0 until 3).map { randomPiece(rng) }
                    val oi = rng.nextInt(3); val op = ps[oi]; val ci = rng.nextInt(op.cells.size)
                    ps.mapIndexed { i, p -> if (i == oi) Piece(p.cells.mapIndexed { j, c -> if (j == ci) Cell(c.r, c.c, 2) else c }) else p }
                }
            }
            // every candidate is evaluated on ALL futures — (candidate × future) pairs run in parallel on all cores
            // LAST ROUND SAFETY: with exactly one round left after this one, sample the final tray directly (feasibility only)
            val lastRisk = DoubleArray(top.size)
            if (roundsLeft == 1 && LAST_TRAY_M > 0) { val trays = (0 until LAST_TRAY_M).map { randomTray(rng) }; val lr = parallelMap(top) { cand -> trays.count { !trayFeasible(cand.board, it) }.toDouble() / trays.size }; for (i in lr.indices) lastRisk[i] = lr[i] }
            // SUCCESSIVE HALVING in 3 waves: after each wave drop candidates trailing the leader by > ROLL_CUT per future
            val sums = DoubleArray(top.size); var alive = top.indices.toList(); var used = 0
            val waves = listOf(0 until futures.size / 3, futures.size / 3 until futures.size * 2 / 3, futures.size * 2 / 3 until futures.size).filter { !it.isEmpty() }
            for ((wi, wave) in waves.withIndex()) {
                if (wi > 0 && alive.size > 2 && ROLL_CUT > 0) { val lead = alive.maxOf { sums[it] }; val keep = alive.filter { lead - sums[it] <= ROLL_CUT * used }; if (keep.isNotEmpty()) alive = keep }
                val jobs = ArrayList<Pair<Int, Int>>(); for (ci in alive) for (fi in wave) jobs.add(ci to fi)
                val vals = parallelMap(jobs) { (ci, fi) ->
                    val cand = top[ci]; val f = futures[fi]
                    var bd = cand.board; var bo = cand.bonus; var mu = cand.mult; var pts = cand.pts.toDouble(); var dead = false
                    for (k in 0 until roundsLeft) {
                        val isPenult = (lvl + 1 + k) == 24 && INNER_SAFE_K > 0 && k + 1 < f.size
                        val pl = planFull(bd, bo, f[k], mu, lvl + 1 + k, ROLL_LEVEL, if (isPenult) f[k + 1] else null)
                        if (pl == null) { dead = true; break }
                        bd = pl.board; bo = pl.bonus; mu = pl.mult; pts += pl.pts
                    }
                    if (!dead) pts += bd.count { it != 0 } * END_CUBE else pts -= ROLL_DEATH + banked
                    pts
                }
                for ((j, v) in vals.withIndex()) sums[jobs[j].first] += v
                used += wave.count()
            }
            if (used > 0) {
                var bestAvg = Double.NEGATIVE_INFINITY
                for (ci in alive) { val avg = sums[ci] / used - lastRisk[ci] * (banked + top[ci].pts + LAST_TRAY_LOSS); if (avg > bestAvg) { bestAvg = avg; b = top[ci] } }
            }
        } else if (trayActive && roundsLeft >= 1 && cands.size > 1 && b.board.count { it != 0 } >= TRAY_CUBES) {
            // NEXT-TRAY SAFETY: the per-piece "cover" term cannot see that THREE pieces must fit TOGETHER. Sample real trays and
            // charge every top candidate the probability that the next tray has no legal placement (= death) — in parallel.
            cands.sortByDescending { it.score }
            // candidate pool: heuristic top-K plus the most OPEN boards (fewest cubes) — the safe plan is often not in the top-K
            val top = ArrayList(cands.take(TRAY_K))
            if (TRAY_K_OPEN > 0) for (n in cands.sortedBy { it.board.count { v -> v != 0 } }) { if (top.size >= TRAY_K + TRAY_K_OPEN) break; if (n !in top) top.add(n) }
            val rng = java.util.Random(4242L + lvl * 15485863L)
            val trays = (0 until TRAY_M).map { randomTray(rng) }
            val rem2 = remAfterRound.coerceAtLeast(0)
            val risks = parallelMap(top) { cand -> trays.count { !trayFeasible(cand.board, it) }.toDouble() / trays.size }
            val costs = DoubleArray(top.size) { DEATH + banked + DEATH_REM * rem2 + rem2 * top[it].mult * STAKE }
            // TWO-ROUND RISK: the next tray may fit but leave a board where the tray AFTER it cannot — play each sampled tray
            // with the fast planner and measure the second tray's infeasibility (discounted by TRAY2_W).
            val risk2 = DoubleArray(top.size)
            if (TRAY2_M > 0 && roundsLeft >= 2) {
                val trays2 = (0 until TRAY2_M).map { randomTray(rng) }
                val order = top.indices.sortedByDescending { top[it].score - risks[it] * costs[it] }.take(TRAY2_K)
                val r2 = parallelMap(order) { i ->
                    var bad = 0; var cnt = 0
                    for (m in 0 until TRAY2_M) {
                        val pl = planFull(top[i].board, top[i].bonus, trays[m % trays.size], top[i].mult, lvl + 1, 1)
                        cnt++; if (pl == null) { bad++; continue }
                        if (!trayFeasible(pl.board, trays2[m])) bad++
                    }
                    if (cnt > 0) bad.toDouble() / cnt else 0.0
                }
                for ((j, i) in order.withIndex()) risk2[i] = r2[j]
            }
            var bestV = Double.NEGATIVE_INFINITY
            for (i in top.indices) { val risk = risks[i] + (1 - risks[i]) * risk2[i] * TRAY2_W; val v = top[i].score - risk * costs[i]; if (v > bestV) { bestV = v; b = top[i] } }
        }
        return Plan(b.moves, b.pts, false, b.mult)
    }

    /** plan() variant used inside rollouts: returns the resulting node (board/bonus/mult/pts) or null if dead. */
    private fun planFull(board: IntArray, bonus: IntArray, pieces: List<Piece>, mult: Int, gameLevel: Int, level: Int, safeLast: List<Piece>? = null): Node? {
        val cfg = LEVELS[level.coerceIn(1, 3)]!!
        val lvl = gameLevel.coerceIn(1, 25); val remAfterRound = (25 - lvl) * 3
        var best: Node? = null
        val cands = ArrayList<Node>()
        for (order in perms(pieces.indices.toList())) {
            var beam = listOf(Node(board, bonus, mult, 0, emptyList()))
            for ((step, slot) in order.withIndex()) {
                val piece = pieces[slot]; val next = ArrayList<Node>()
                val remaining = remAfterRound + (order.size - 1 - step)
                for (node in beam) for (rc in Engine.allPlacements(node.board, piece)) {
                    val res = Engine.place(node.board, node.bonus, piece, rc[0], rc[1])
                    val (pts, nm) = realPoints(res, node.mult)
                    val nn = Node(res.board, res.bonus, nm, node.pts + pts, emptyList())
                    nn.score = evaluate(nn, mult, remaining, cfg)
                    next.add(nn)
                }
                if (next.isEmpty()) { beam = emptyList(); break }
                next.sortByDescending { it.score }
                beam = next.take(if (step == order.size - 1) (if (safeLast != null) INNER_SAFE_K else 1) else cfg.beam)
            }
            if (beam.isNotEmpty()) { cands.addAll(beam); if (best == null || beam[0].score > best.score) best = beam[0] }
        }
        // SAFE-LAST (second-to-last round inside rollouts): among the kept candidates prefer the board where the FINAL tray fits
        if (safeLast != null && best != null && cands.size > 1) {
            cands.sortByDescending { it.score }
            val top = cands.take(INNER_SAFE_K)
            val rng = java.util.Random(999L + lvl)
            val trays = ArrayList<List<Piece>>(); trays.add(safeLast); for (m in 1 until INNER_SAFE_M) trays.add(randomTray(rng))
            var bestV = Double.NEGATIVE_INFINITY
            for (n in top) { val r = trays.count { !trayFeasible(n.board, it) }.toDouble() / trays.size; val v = n.score - r * LAST_TRAY_LOSS; if (v > bestV) { bestV = v; best = n } }
        }
        return best
    }
}

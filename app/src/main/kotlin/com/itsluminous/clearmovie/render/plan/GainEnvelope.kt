package com.itsluminous.clearmovie.render.plan

import kotlin.math.pow

/**
 * A piecewise-linear gain curve in decibels over a segment's own time. Before
 * the first point and after the last the curve is flat. A value at or below
 * [SILENT_DB] means silence. Sums of curves (volume + ducking + fades +
 * crossfade) are taken point-wise over the union of their breakpoints.
 */
data class GainEnvelope(
    val points: List<GainPoint>,
) {
    init {
        require(points.isNotEmpty()) { "An envelope needs at least one point" }
        require(points.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) { "Envelope points must be in time order" }
    }

    fun dbAt(timeUs: Long): Double {
        if (timeUs <= points.first().timeUs) return points.first().db
        if (timeUs >= points.last().timeUs) return points.last().db
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timeUs <= timeUs) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (b.timeUs == a.timeUs) return b.db
        val f = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
        return a.db + (b.db - a.db) * f
    }

    /** The linear amplitude factor at [timeUs]. */
    fun gainAt(timeUs: Long): Float = dbToGain(dbAt(timeUs))

    val isUnity: Boolean get() = points.all { it.db == 0.0 }

    /** This curve plus [other], in dB. */
    operator fun plus(other: GainEnvelope): GainEnvelope {
        val times = (points.map { it.timeUs } + other.points.map { it.timeUs }).distinct().sorted()
        return GainEnvelope(times.map { GainPoint(it, (dbAt(it) + other.dbAt(it)).coerceAtLeast(SILENT_DB)) }).simplified()
    }

    /** Drops points that lie on the straight line through their neighbours. */
    fun simplified(): GainEnvelope {
        if (points.size <= 2) return this
        val kept = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            val a = kept.last()
            val p = points[i]
            val b = points[i + 1]
            val onLine =
                b.timeUs != a.timeUs &&
                    kotlin.math.abs(a.db + (b.db - a.db) * (p.timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs) - p.db) < 1e-6
            if (!onLine) kept += p
        }
        kept += points.last()
        return GainEnvelope(kept)
    }

    /** The part of this curve between [fromUs] and [toUs], shifted so [fromUs] becomes 0. */
    fun slice(
        fromUs: Long,
        toUs: Long,
    ): GainEnvelope {
        val inner = points.filter { it.timeUs > fromUs && it.timeUs < toUs }.map { GainPoint(it.timeUs - fromUs, it.db) }
        return GainEnvelope(listOf(GainPoint(0, dbAt(fromUs))) + inner + GainPoint(toUs - fromUs, dbAt(toUs))).simplified()
    }

    companion object {
        /** At or below this, a sample is silent. */
        const val SILENT_DB = -100.0

        fun constant(db: Double): GainEnvelope = GainEnvelope(listOf(GainPoint(0, db.coerceAtLeast(SILENT_DB))))

        val UNITY = constant(0.0)

        fun dbToGain(db: Double): Float = if (db <= SILENT_DB + 1e-9) 0f else 10.0.pow(db / 20.0).toFloat()
    }
}

data class GainPoint(
    val timeUs: Long,
    val db: Double,
)

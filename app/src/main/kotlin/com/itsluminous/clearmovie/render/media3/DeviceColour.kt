package com.itsluminous.clearmovie.render.media3

/**
 * How this device's video pipeline treats BT.709 SDR video, as measured by
 * [ColourCalibration]. Media3 decodes SDR video through the GPU's
 * `samplerExternalOES`, so the YUV-to-RGB matrix is the driver's, and the
 * encoder's RGB-to-YUV matrix is the encoder's: neither is guaranteed to
 * follow the stream's tag (the Android emulators get both wrong, see
 * export-colour notes in docs/architecture.md).
 *
 * @property gpuDecodesBt709AsBt601 the GPU turns BT.709 video into RGB with
 *   the BT.601 matrix, so BT.709 video sources need [ColourMath.bt601DecodedToBt709].
 * @property encoderWritesBt601 the encoder turns RGB into YUV with the
 *   BT.601 matrix while the requested tag is BT.709, so the export asks it for
 *   BT.601 and the file's tag matches what it holds.
 * @property measured false when the calibration could not tell (both flags
 *   are then false and nothing is corrected).
 * @property encoderName the video encoder the calibration measured; the BT.601
 *   tag is asked for only when an export gets that same encoder.
 */
data class DeviceColour(
    val gpuDecodesBt709AsBt601: Boolean,
    val encoderWritesBt601: Boolean,
    val measured: Boolean = true,
    val encoderName: String? = null,
) {
    /** One line for the cache file, tied to [key] (the calibration version and build fingerprint). */
    fun serialize(key: String): String =
        "$key|${flag(gpuDecodesBt709AsBt601)}|${flag(encoderWritesBt601)}|${flag(measured)}|${encoderName.orEmpty()}"

    companion object {
        /** Trust the device: no correction, no change of tag. */
        val TRUSTED = DeviceColour(gpuDecodesBt709AsBt601 = false, encoderWritesBt601 = false, measured = false)

        private fun flag(b: Boolean) = if (b) "1" else "0"

        /** Codec names are dotted identifiers ("c2.android.avc.encoder"). */
        private val CODEC_NAME = Regex("[A-Za-z0-9._-]{1,128}")

        /** The cached result for [key], or null when [line] is for another key or is malformed. */
        fun parse(
            line: String,
            key: String,
        ): DeviceColour? {
            val text = line.trim()
            if (!text.startsWith("$key|")) return null
            val fields = text.removePrefix("$key|").split('|')
            if (fields.size != 4) return null
            val flags = fields.take(3)
            if (flags.any { it != "0" && it != "1" }) return null
            val name = fields[3].takeIf { it.isNotEmpty() }
            if (name != null && !CODEC_NAME.matches(name)) return null
            return DeviceColour(flags[0] == "1", flags[1] == "1", flags[2] == "1", name)
        }
    }
}

/** BT.601 / BT.709 conversions for 8-bit limited-range video, in electrical (gamma) RGB 0..255. */
object ColourMath {
    enum class Matrix(
        val kr: Double,
        val kb: Double,
    ) {
        BT709(0.2126, 0.0722),
        BT601(0.299, 0.114),
    }

    /** Y, Cb, Cr (16..235, 16..240) of [rgb]. */
    fun toYuv(
        rgb: DoubleArray,
        m: Matrix,
    ): DoubleArray {
        val (y, pb, pr) = yPbPr(rgb.map { it / 255 }.toDoubleArray(), m)
        return doubleArrayOf(16 + 219 * y, 128 + 224 * pb, 128 + 224 * pr)
    }

    /** RGB of [yuv], not clipped. */
    fun toRgb(
        yuv: DoubleArray,
        m: Matrix,
    ): DoubleArray = rgb(doubleArrayOf((yuv[0] - 16) / 219, (yuv[1] - 128) / 224, (yuv[2] - 128) / 224), m).map { it * 255 }.toDoubleArray()

    private fun yPbPr(
        rgb: DoubleArray,
        m: Matrix,
    ): DoubleArray {
        val kg = 1 - m.kr - m.kb
        val y = m.kr * rgb[0] + kg * rgb[1] + m.kb * rgb[2]
        return doubleArrayOf(y, (rgb[2] - y) / (2 * (1 - m.kb)), (rgb[0] - y) / (2 * (1 - m.kr)))
    }

    private fun rgb(
        yPbPr: DoubleArray,
        m: Matrix,
    ): DoubleArray {
        val (y, pb, pr) = yPbPr
        val r = y + 2 * (1 - m.kr) * pr
        val b = y + 2 * (1 - m.kb) * pb
        val g = (y - m.kr * r - m.kb * b) / (1 - m.kr - m.kb)
        return doubleArrayOf(r, g, b)
    }

    /**
     * The 4x4 column-major colour matrix (Media3 `RgbMatrix` layout) that
     * takes RGB a decoder produced from BT.709 YUV with the BT.601 matrix to
     * the RGB the BT.709 matrix gives. Both decodes share the YUV, so it is
     * BT.709-decode times BT.601-encode, linear in electrical RGB with no
     * offset (the range scaling cancels). Values the misdecode clipped at 0
     * or 1 cannot be recovered exactly.
     */
    fun bt601DecodedToBt709(): FloatArray {
        val out = FloatArray(16)
        for (j in 0 until 3) {
            val basis = DoubleArray(3).also { it[j] = 1.0 }
            val column = rgb(yPbPr(basis, Matrix.BT601), Matrix.BT709)
            for (i in 0 until 3) out[j * 4 + i] = column[i].toFloat()
        }
        out[15] = 1f
        return out
    }

    /** [m] applied to [rgb] (0..255), clipped, as the GPU would. */
    fun apply(
        m: FloatArray,
        rgb: DoubleArray,
    ): DoubleArray = DoubleArray(3) { i -> (0 until 3).sumOf { j -> m[j * 4 + i] * rgb[j] }.coerceIn(0.0, 255.0) }

    /**
     * Which of [candidates] [observed] is: the index of the nearest when it is
     * within [maxError] in every channel and every other candidate is at least
     * [minGap] further away; null when the measurement does not tell.
     */
    fun nearest(
        observed: DoubleArray,
        candidates: List<DoubleArray>,
        maxError: Double = 8.0,
        minGap: Double = 6.0,
    ): Int? {
        val errors = candidates.map { c -> c.indices.maxOf { kotlin.math.abs(c[it] - observed[it]) } }
        val best = errors.indices.minByOrNull { errors[it] } ?: return null
        if (errors[best] > maxError) return null
        if (errors.indices.any { it != best && errors[it] - errors[best] < minGap }) return null
        return best
    }
}

package com.itsluminous.clearmovie.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An ARGB colour, written in JSON as `#RRGGBB` (opaque) or `#AARRGGBB`. */
@JvmInline
@Serializable(with = ArgbColorSerializer::class)
value class ArgbColor(
    val argb: Long,
) {
    init {
        require(argb in 0L..0xFFFFFFFFL) { "Colour out of range" }
    }

    val alpha: Int get() = ((argb shr 24) and 0xFF).toInt()

    /** `#RRGGBB` when opaque, else `#AARRGGBB`; upper-case and locale-independent. */
    override fun toString(): String =
        if (alpha == 0xFF) {
            "#" + hex(argb and 0xFFFFFFL, 6)
        } else {
            "#" + hex(argb, 8)
        }

    private fun hex(
        value: Long,
        digits: Int,
    ): String =
        java.lang.Long
            .toHexString(value)
            .uppercase(java.util.Locale.ROOT)
            .padStart(digits, '0')

    companion object {
        private val HEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

        val WHITE = ArgbColor(0xFFFFFFFFL)
        val BLACK = ArgbColor(0xFF000000L)

        /** Parses `#RRGGBB` or `#AARRGGBB`; null for anything else. */
        fun parse(text: String): ArgbColor? {
            if (!HEX.matches(text)) return null
            val digits = text.substring(1)
            val value = digits.toLong(16)
            return ArgbColor(if (digits.length == 6) 0xFF000000L or value else value)
        }
    }
}

internal object ArgbColorSerializer : KSerializer<ArgbColor> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ArgbColor", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ArgbColor,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): ArgbColor {
        val text = decoder.decodeString()
        return requireNotNull(ArgbColor.parse(text)) { "Invalid colour" }
    }
}

/** A rectangle in normalised canvas coordinates (0..1 on each axis). */
@Serializable
data class NormRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    /** Inside the canvas, with a positive size. */
    val isValid: Boolean
        get() =
            listOf(x, y, w, h).all { it.isFinite() } &&
                x >= 0.0 && y >= 0.0 && w > 0.0 && h > 0.0 &&
                x + w <= 1.0 + EPSILON && y + h <= 1.0 + EPSILON

    companion object {
        private const val EPSILON = 1e-9

        /** The whole canvas. */
        val FULL = NormRect(0.0, 0.0, 1.0, 1.0)
    }
}

@Serializable
enum class TextAlign {
    @SerialName("start")
    START,

    @SerialName("center")
    CENTER,

    @SerialName("end")
    END,
}

@Serializable
data class TextOutline(
    val color: ArgbColor,
    val widthPx: Int,
)

@Serializable
data class TextBackground(
    val color: ArgbColor,
    val cornerPx: Int = 0,
)

/**
 * A caption's full style (section 8, `caption`). The preset supplies one per
 * project; a text clip stores only what the user changed, as a
 * [CaptionStyleOverride], so **Reset to preset** is simply clearing it.
 * [font] is a bundled font id; the renderer falls back if it does not know it.
 */
@Serializable
data class CaptionStyle(
    val box: NormRect = DEFAULT_BOX,
    val align: TextAlign = TextAlign.CENTER,
    val font: String = DEFAULT_FONT,
    val sizePx: Int = DEFAULT_SIZE_PX,
    val color: ArgbColor = ArgbColor.WHITE,
    val outline: TextOutline? = TextOutline(ArgbColor.BLACK, 4),
    val background: TextBackground? = TextBackground(ArgbColor(0x99000000L), 12),
) {
    companion object {
        /** Above the reference frame's caption strip and clear of its logo (ARCHITECTURE section 3). */
        val DEFAULT_BOX = NormRect(0.16, 0.62, 0.81, 0.14)
        const val DEFAULT_FONT = "noto_sans_devanagari_bold"
        const val DEFAULT_SIZE_PX = 64
        const val MIN_SIZE_PX = 8
        const val MAX_SIZE_PX = 400
        const val MAX_OUTLINE_PX = 64
        const val MAX_CORNER_PX = 200
        val FONT_PATTERN = Regex("^[a-z0-9_]{1,64}$")
    }
}

/**
 * The fields of a caption's style the user changed. A null field means "the
 * project's caption default". [clearOutline] and [clearBackground] switch the
 * outline or background off even when the default has one.
 */
@Serializable
data class CaptionStyleOverride(
    val box: NormRect? = null,
    val align: TextAlign? = null,
    val font: String? = null,
    val sizePx: Int? = null,
    val color: ArgbColor? = null,
    val outline: TextOutline? = null,
    val clearOutline: Boolean = false,
    val background: TextBackground? = null,
    val clearBackground: Boolean = false,
) {
    val isEmpty: Boolean get() = this == NONE

    /** The style a renderer uses: the defaults with these changes applied. */
    fun resolve(defaults: CaptionStyle): CaptionStyle =
        CaptionStyle(
            box = box ?: defaults.box,
            align = align ?: defaults.align,
            font = font ?: defaults.font,
            sizePx = sizePx ?: defaults.sizePx,
            color = color ?: defaults.color,
            outline = if (clearOutline) null else outline ?: defaults.outline,
            background = if (clearBackground) null else background ?: defaults.background,
        )

    companion object {
        val NONE = CaptionStyleOverride()
    }
}

/** Problems with a caption style, for validation. Empty when it is usable. */
internal fun CaptionStyle.problems(): List<String> =
    buildList {
        if (!box.isValid) add("caption box is outside the canvas")
        if (!CaptionStyle.FONT_PATTERN.matches(font)) add("caption font id is invalid")
        if (sizePx !in CaptionStyle.MIN_SIZE_PX..CaptionStyle.MAX_SIZE_PX) add("caption size is out of range")
        outline?.let { if (it.widthPx !in 0..CaptionStyle.MAX_OUTLINE_PX) add("caption outline width is out of range") }
        background?.let { if (it.cornerPx !in 0..CaptionStyle.MAX_CORNER_PX) add("caption corner radius is out of range") }
    }

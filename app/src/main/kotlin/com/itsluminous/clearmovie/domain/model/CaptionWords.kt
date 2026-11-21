package com.itsluminous.clearmovie.domain.model

/** The words a new caption starts with when its preset (or the lack of one) sets none of its own. */
object CaptionWords {
    /** The same English words in every app language; the user changes them in the caption editor. */
    const val DEFAULT = "The image caption"

    /**
     * The Event frame preset's caption words in 0.0.2 and 0.0.3, meant for screenshots only and retired since.
     * An install can still hold them: in an edited copy of that preset (saved with its words left as they were)
     * or in a video made from it. They are read as no words, so new captions there start with [DEFAULT]; words
     * the user typed are stored in every language ([LocalizedText.same]), so they never match and are kept.
     * Written as escapes so the source no longer spells them out.
     */
    internal val RETIRED = LocalizedText(hi = "\u0938\u0941\u0902\u0926\u0930 \u092A\u094D\u0930\u0915\u0943\u0924\u093F")

    /** [text], or null when it is the retired words. */
    fun withoutRetired(text: LocalizedText?): LocalizedText? = text?.takeUnless { it == RETIRED }
}

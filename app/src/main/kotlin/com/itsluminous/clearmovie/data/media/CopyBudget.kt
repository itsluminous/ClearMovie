package com.itsluminous.clearmovie.data.media

/**
 * The copy budget (ARCHITECTURE section 9, review F4 and F13). Before
 * anything is copied, the sizes are added up. If one intake would copy more
 * than [INTAKE_LIMIT_BYTES], or leave less than [KEEP_FREE_BYTES] free, the
 * user is asked first ("These N photos and videos need X GB of space on your
 * phone", with Copy anyway, Choose fewer and, under partial access, Allow all
 * photos). A copy that would not fit at all, keeping [RESERVE_BYTES] for the
 * phone itself, is refused.
 */
object CopyBudget {
    const val INTAKE_LIMIT_BYTES = 1_000_000_000L
    const val KEEP_FREE_BYTES = 2_000_000_000L
    const val RESERVE_BYTES = 200_000_000L

    sealed interface Verdict {
        /** Nothing to copy, or a small copy: go ahead without asking. */
        data object Fits : Verdict

        data class NeedsConfirmation(
            val itemCount: Int,
            val totalBytes: Long,
            val freeBytes: Long,
            /** Under partial access, "Allow all photos" avoids copying altogether. */
            val offerAllowAll: Boolean,
        ) : Verdict

        data class NotEnoughSpace(
            val itemCount: Int,
            val totalBytes: Long,
            val freeBytes: Long,
        ) : Verdict
    }

    /**
     * @param copyBytes the size of each item that would be copied; an unknown
     *   size (negative) counts as [UNKNOWN_SIZE_GUESS].
     */
    fun evaluate(
        copyBytes: List<Long>,
        freeBytes: Long,
        partialAccess: Boolean,
    ): Verdict {
        if (copyBytes.isEmpty()) return Verdict.Fits
        val total = copyBytes.sumOf { if (it < 0) UNKNOWN_SIZE_GUESS else it }
        return when {
            total + RESERVE_BYTES > freeBytes -> {
                Verdict.NotEnoughSpace(copyBytes.size, total, freeBytes)
            }

            total > INTAKE_LIMIT_BYTES || freeBytes - total < KEEP_FREE_BYTES -> {
                Verdict.NeedsConfirmation(copyBytes.size, total, freeBytes, offerAllowAll = partialAccess)
            }

            else -> {
                Verdict.Fits
            }
        }
    }

    /** A provider that does not say how big a file is: assume a short phone video. */
    const val UNKNOWN_SIZE_GUESS = 100_000_000L
}

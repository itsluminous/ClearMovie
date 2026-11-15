package com.itsluminous.clearmovie.data.media

/**
 * Photo and video access (ARCHITECTURE section 9): which permissions to ask
 * for on each Android version, and what the answer means. The app asks once,
 * on a plain-language explanation screen, before the first project.
 *
 * - API 33+: `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`, plus
 *   `READ_MEDIA_VISUAL_USER_SELECTED` on API 34+, where the user may choose
 *   "Select photos" (partial access).
 * - API 29-32: `READ_EXTERNAL_STORAGE`.
 */
object MediaPermissions {
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /** The permissions to request together on [sdk]. */
    fun toRequest(sdk: Int): List<String> =
        when {
            sdk >= 34 -> listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
            sdk >= 33 -> listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
            else -> listOf(READ_EXTERNAL_STORAGE)
        }

    /**
     * The access state from what the system reports.
     *
     * @param granted the permissions currently granted.
     * @param askedBefore whether the app has asked before (saved in settings).
     * @param canAskAgain whether `shouldShowRequestPermissionRationale` is true
     *   for any requested permission. After a refusal it is true until the user
     *   refuses again, when Android stops showing the request at all.
     */
    fun state(
        sdk: Int,
        granted: Set<String>,
        askedBefore: Boolean,
        canAskAgain: Boolean,
    ): MediaAccessState {
        val full =
            if (sdk >= 33) {
                READ_MEDIA_IMAGES in granted && READ_MEDIA_VIDEO in granted
            } else {
                READ_EXTERNAL_STORAGE in granted
            }
        return when {
            full -> MediaAccessState.Full
            sdk >= 34 && READ_MEDIA_VISUAL_USER_SELECTED in granted -> MediaAccessState.Partial
            !askedBefore -> MediaAccessState.NotAsked
            canAskAgain -> MediaAccessState.Denied
            else -> MediaAccessState.DeniedForGood
        }
    }
}

/** What ClearMovie may read from the gallery. */
enum class MediaAccessState {
    /** Never asked: show the explanation screen before the first project. */
    NotAsked,

    /** Every photo and video: items are kept as MediaStore links. */
    Full,

    /**
     * Only the photos the user selected (API 34+). Items outside that set can
     * not be opened by link later, so they are kept by a persisted grant or
     * copied; a banner offers "Allow all photos".
     */
    Partial,

    /** Refused, and Android will still show the request: "Try again". */
    Denied,

    /** Refused for good: only the app's page in Android Settings can change it. */
    DeniedForGood,
    ;

    /** Whether the explanation screen should be shown before the first project. */
    val shouldExplain: Boolean get() = this == NotAsked

    /** Whether MediaStore links to any photo or video stay readable later. */
    val isFull: Boolean get() = this == Full
}

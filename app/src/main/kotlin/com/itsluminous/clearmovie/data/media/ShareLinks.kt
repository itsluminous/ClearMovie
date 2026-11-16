package com.itsluminous.clearmovie.data.media

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.itsluminous.clearmovie.domain.model.MediaRef

/**
 * The links a share carries, read from an untrusted intent (ARCHITECTURE
 * section 11.12). [uris] are `content://` links, each at most once, in the
 * order shared, and never more than [ShareLinks.MAX_ITEMS]. [refused]
 * counts entries that are not links the app opens (`file://` and other
 * schemes, or malformed ones); [overLimit] counts links left out past the
 * limit.
 */
data class SharedLinks(
    val uris: List<Uri>,
    val refused: Int = 0,
    val overLimit: Int = 0,
) {
    val isEmpty: Boolean get() = uris.isEmpty()
}

/** Reads [SharedLinks] from `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents. */
object ShareLinks {
    /** The most photos and videos one share can add (review F25 allows more than the picker's 100, with a notice). */
    const val MAX_ITEMS = 500

    /** The internal action `ShareActivity` forwards a share to `MainActivity` with. */
    const val ACTION_SHARED = "com.itsluminous.clearmovie.action.SHARED"

    /** Counts the share layer carried over from the first reading (in `ShareActivity`). */
    const val EXTRA_REFUSED = "com.itsluminous.clearmovie.extra.REFUSED"
    const val EXTRA_OVER_LIMIT = "com.itsluminous.clearmovie.extra.OVER_LIMIT"

    /** Entries read from one intent at most, so a huge or crafted list can never make the app loop for long. */
    private const val MAX_ENTRIES = 10_000

    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE || intent?.action == ACTION_SHARED

    /**
     * The links in [intent], or null when it is not a share. Every read is
     * guarded: an extra of the wrong type or a parcel that fails to unpack
     * counts as nothing shared, never a crash.
     */
    fun read(
        intent: Intent?,
        maxItems: Int = MAX_ITEMS,
    ): SharedLinks? {
        if (intent == null || !isShare(intent)) return null
        val raw = mutableListOf<Uri?>()
        raw += streamExtra(intent)
        if (raw.isEmpty()) raw += clipItems(intent)
        val carried =
            if (intent.action == ACTION_SHARED) {
                safely { intent.getIntExtra(EXTRA_REFUSED, 0) }.orZero() to safely { intent.getIntExtra(EXTRA_OVER_LIMIT, 0) }.orZero()
            } else {
                0 to 0
            }
        return sanitize(raw, maxItems).let {
            it.copy(
                refused = it.refused + carried.first.coerceIn(0, MAX_ENTRIES),
                overLimit =
                    it.overLimit + carried.second.coerceIn(0, MAX_ENTRIES),
            )
        }
    }

    /** Keeps `content://` links the model accepts, once each and in order, up to [maxItems]. */
    fun sanitize(
        raw: List<Uri?>,
        maxItems: Int = MAX_ITEMS,
    ): SharedLinks {
        val seen = LinkedHashSet<String>()
        val kept = mutableListOf<Uri>()
        var refused = 0
        var over = 0
        for (uri in raw.take(MAX_ENTRIES)) {
            val text = uri?.toString()
            if (uri == null || text == null || uri.scheme != ContentResolver.SCHEME_CONTENT || !MediaRef.isSafeContentUri(text)) {
                refused++
                continue
            }
            if (!seen.add(text)) continue
            if (kept.size >= maxItems) over++ else kept += uri
        }
        return SharedLinks(kept, refused, over + (raw.size - MAX_ENTRIES).coerceAtLeast(0))
    }

    private fun streamExtra(intent: Intent): List<Uri?> =
        when (intent.action) {
            Intent.ACTION_SEND -> {
                listOfNotNull(safely { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) })
            }

            else -> {
                safely { IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.orEmpty()
            }
        }

    private fun clipItems(intent: Intent): List<Uri?> {
        val clip = safely { intent.clipData } ?: return emptyList()
        val count = safely { clip.itemCount }.orZero().coerceAtMost(MAX_ENTRIES)
        return (0 until count).map { i -> safely { clip.getItemAt(i)?.uri } }
    }

    private fun Int?.orZero() = this ?: 0

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (e: RuntimeException) {
            // BadParcelableException, ClassCastException and the like: an intent built to break the app.
            null
        }
}

package com.itsluminous.clearmovie

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.itsluminous.clearmovie.data.media.ShareHandoff
import com.itsluminous.clearmovie.data.media.ShareLinks
import com.itsluminous.clearmovie.data.media.SharedLinks

/**
 * Where a share from the gallery lands (`SEND` and `SEND_MULTIPLE` of
 * photos and videos). Android starts a share target inside the sharing
 * app's task, so the share is handed on to the one [MainActivity], in
 * ClearMovie's own task: a share while a video is open reaches it there (its
 * `onNewIntent`) instead of a second copy of the app editing beside it.
 *
 * The intent is untrusted. Only its `content://` links go on: kept in
 * [ShareHandoff] under a one-time token (so no other app can fake a
 * handed-on share), and repeated in the new intent's ClipData with a read
 * grant, so they stay readable after this activity finishes. It shows
 * nothing and finishes at once.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = ShareLinks.readSafely(intent)
        if (shared != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) forward(shared)
        finish()
    }

    /**
     * A link this app was not granted cannot be handed on with a grant, and
     * the hand-off then fails as a whole: the share is dropped with a note,
     * never retried without the grant (that would let the app read, with its
     * own photo access, items the sender itself could not share).
     */
    private fun forward(shared: SharedLinks) {
        val token = ShareHandoff.put(shared)
        try {
            startActivity(forwardIntent(shared, token))
        } catch (e: RuntimeException) {
            // SecurityException (a link without a grant), or the system refused the hand-off.
            ShareHandoff.take(token)
            val localized = ContextCompat.getContextForLanguage(this)
            runCatching { Toast.makeText(localized, localized.getString(R.string.share_could_not_start), Toast.LENGTH_LONG).show() }
        }
    }

    private fun forwardIntent(
        shared: SharedLinks,
        token: String,
    ): Intent {
        val uris = shared.uris
        return Intent(this, MainActivity::class.java).apply {
            action = ShareLinks.ACTION_SHARED
            putExtra(ShareHandoff.EXTRA_TOKEN, token)
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

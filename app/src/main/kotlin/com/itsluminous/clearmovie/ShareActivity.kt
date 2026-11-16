package com.itsluminous.clearmovie

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import com.itsluminous.clearmovie.data.media.ShareLinks

/**
 * Where a share from the gallery lands (`SEND` and `SEND_MULTIPLE` of
 * photos and videos). Android starts a share target inside the sharing
 * app's task, so the share is handed on to the one [MainActivity], in
 * ClearMovie's own task: a share while a video is open reaches it there (its
 * `onNewIntent`) instead of a second copy of the app editing beside it.
 *
 * The intent is untrusted. Only its `content://` links go on, in a new
 * intent with nothing else of the original: no other extras, no flags but a
 * read grant, so the links stay readable after this activity finishes. It
 * shows nothing and finishes at once.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = ShareLinks.read(intent)
        if (shared != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            forward(shared.uris.isNotEmpty(), forwardIntent(shared))
        }
        finish()
    }

    private fun forward(
        withGrant: Boolean,
        intent: Intent,
    ) {
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            // A link this app cannot hand on (it was never granted one): pass the links without the grant; they then count as unreadable.
            if (withGrant) runCatching { startActivity(intent.apply { removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
        } catch (e: RuntimeException) {
            // Nothing to do: the share is dropped rather than crash in the sharing app's task.
        }
    }

    private fun forwardIntent(shared: com.itsluminous.clearmovie.data.media.SharedLinks): Intent {
        val uris = ArrayList(shared.uris)
        return Intent(this, MainActivity::class.java).apply {
            action = ShareLinks.ACTION_SHARED
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(ShareLinks.EXTRA_REFUSED, shared.refused)
            putExtra(ShareLinks.EXTRA_OVER_LIMIT, shared.overLimit)
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

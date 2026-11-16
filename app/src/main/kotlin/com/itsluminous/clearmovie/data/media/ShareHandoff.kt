package com.itsluminous.clearmovie.data.media

import java.util.UUID

/**
 * Hands a share from `ShareActivity` to `MainActivity` inside the app's own
 * process. `MainActivity` is exported (it is the launcher entry), so any app
 * could send it an intent that looks like a handed-on share; only a token
 * `ShareActivity` put here, used once, is accepted, and the links and counts
 * come from here, never from the intent's extras.
 */
object ShareHandoff {
    const val EXTRA_TOKEN = "com.itsluminous.clearmovie.extra.SHARE_TOKEN"

    /** Shares waiting to be taken at most; the oldest goes first. */
    private const val MAX_HELD = 8

    private val held = LinkedHashMap<String, SharedLinks>()

    /** Keeps [links] for `MainActivity`; returns the token to pass on. */
    @Synchronized
    fun put(links: SharedLinks): String {
        val token = UUID.randomUUID().toString()
        held[token] = links
        while (held.size > MAX_HELD) held.remove(held.keys.first())
        return token
    }

    /** The share [token] names, once; null for a token this process never gave out or one already taken. */
    @Synchronized
    fun take(token: String?): SharedLinks? = token?.let { held.remove(it) }
}

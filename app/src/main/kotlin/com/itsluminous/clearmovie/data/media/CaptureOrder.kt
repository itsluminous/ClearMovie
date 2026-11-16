package com.itsluminous.clearmovie.data.media

/**
 * The order shared photos and videos go onto the timeline (ARCHITECTURE
 * section 11.12): the order they were taken, oldest first.
 *
 * The rule for a mixed or unknown order: an item whose capture time is
 * known is sorted by it into the places that dated items hold; an item with
 * no capture time keeps the place it had in the share. Items taken at the
 * same moment keep their shared order (the sort is stable). So a share with
 * no dates at all keeps the gallery's own order, and one with every date
 * known plays in the order taken. Pure, so it is tested on the JVM.
 */
object CaptureOrder {
    fun <T> order(
        items: List<T>,
        takenAt: (T) -> Long?,
    ): List<T> {
        val times = items.map(takenAt)
        val datedSlots = times.indices.filter { times[it] != null }
        val sorted = datedSlots.sortedWith(compareBy<Int> { times[it]!! }.thenBy { it })
        val out = items.toMutableList()
        datedSlots.forEachIndexed { k, slot -> out[slot] = items[sorted[k]] }
        return out
    }
}

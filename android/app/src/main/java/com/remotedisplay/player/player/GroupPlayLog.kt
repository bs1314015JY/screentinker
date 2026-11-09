package com.remotedisplay.player.player

/**
 * Proof-of-play for clock-scheduled group sync.
 *
 * A video wall follower does not log: the leader's one row stands for the wall. Group sync reused
 * that follower flag so the schedule, not a local timer, owns the index — and the log went with it.
 * A group has no leader, so every member's plays disappeared the moment sync engaged. Ordinary
 * playback records one play per showing (play_start when it goes up, play_end when it is left).
 * A synced member has to do the same, once per schedule slot.
 *
 * A slot is one occupancy of one item: the timeline cycle, the index, and which item that index
 * is. A one-item loop never changes index, so the cycle is what makes the next pass a new play.
 * The same slot reported again (the 4Hz tick) is the same play and emits nothing.
 *
 * The first slot this session observes only STARTs. It must not END. The row a device had open
 * from before it joined the group belongs to that earlier showing; closing it now would stamp
 * "ended just now" on a play that may have been open for days.
 *
 * No Android types. PlaylistController owns the Handler, so this is the part a JVM test can pin.
 */
object GroupPlayLog {
    enum class Event { END, START }

    /** Stable identity of one showing. [itemKey] is PlaylistItem.itemKey (content id, or widget id). */
    fun slotKey(cycle: Long, index: Int, itemKey: String): String = "$cycle:$index:$itemKey"

    /**
     * @param openSlot the slot we already opened a play for, or null if this member has not logged
     *                 a group-sync play yet
     * @param openLogs whether that open slot's item has log_play on. Ignored when [openSlot] is null.
     * @param nextLogs whether the item now showing has log_play on
     */
    fun events(openSlot: String?, nextSlot: String, openLogs: Boolean, nextLogs: Boolean): List<Event> {
        if (openSlot == nextSlot) return emptyList()
        val out = ArrayList<Event>(2)
        // A slot we never opened (log_play off, or the first slot of this session) has nothing to close.
        if (openSlot != null && openLogs) out.add(Event.END)
        if (nextLogs) out.add(Event.START)
        return out
    }

    /**
     * The schedule has no slot (every item is outside its daypart). Close an open play. Do not
     * open another — there is nothing on the schedule to attribute.
     */
    fun idle(openSlot: String?, openLogs: Boolean): List<Event> =
        if (openSlot != null && openLogs) listOf(Event.END) else emptyList()
}

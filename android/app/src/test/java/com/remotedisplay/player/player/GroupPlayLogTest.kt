package com.remotedisplay.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group sync used to record nothing. It sets the wall-follower flag so the schedule owns the
 * index, and proof-of-play was suppressed for every follower — correct for a video wall, where
 * the leader's row stands for the wall, and wrong for a group, which has no leader.
 *
 * These pin the rule the schedule tick applies. They do not construct PlaylistController: that
 * class owns a Handler and a Looper and cannot run on the JVM. Video-wall followers never call
 * [GroupPlayLog]; their suppression stays the `if (!wallFollower)` gate in playCurrentItem.
 */
class GroupPlayLogTest {

    @Test
    fun `the first slot starts a play and does not end one`() {
        // Ending here would close whatever row was left open before sync, and stamp it as
        // finishing now. A row that has been open since the device joined the group must be
        // left for the server's own stranded-play sweep.
        val ev = GroupPlayLog.events(
            openSlot = null,
            nextSlot = GroupPlayLog.slotKey(5, 0, "clip|"),
            openLogs = true,
            nextLogs = true
        )
        assertEquals(listOf(GroupPlayLog.Event.START), ev)
    }

    @Test
    fun `the same slot on later ticks emits nothing`() {
        val key = GroupPlayLog.slotKey(5, 0, "clip|")
        assertTrue(GroupPlayLog.events(key, key, openLogs = true, nextLogs = true).isEmpty())
    }

    @Test
    fun `a new cycle of the same item is a new play`() {
        val prev = GroupPlayLog.slotKey(5, 0, "clip|")
        val next = GroupPlayLog.slotKey(6, 0, "clip|")
        assertEquals(
            listOf(GroupPlayLog.Event.END, GroupPlayLog.Event.START),
            GroupPlayLog.events(prev, next, openLogs = true, nextLogs = true)
        )
    }

    @Test
    fun `a new index in the same cycle is a new play`() {
        val prev = GroupPlayLog.slotKey(5, 0, "a|")
        val next = GroupPlayLog.slotKey(5, 1, "b|")
        assertEquals(
            listOf(GroupPlayLog.Event.END, GroupPlayLog.Event.START),
            GroupPlayLog.events(prev, next, openLogs = true, nextLogs = true)
        )
    }

    @Test
    fun `swapping the item under the same index is a new play`() {
        // A republish that keeps the schedule position but changes the content must not keep
        // reporting the item that was there before.
        val prev = GroupPlayLog.slotKey(5, 0, "old|")
        val next = GroupPlayLog.slotKey(5, 0, "new|")
        assertEquals(
            listOf(GroupPlayLog.Event.END, GroupPlayLog.Event.START),
            GroupPlayLog.events(prev, next, openLogs = true, nextLogs = true)
        )
    }

    @Test
    fun `log_play off is not recorded, and it still closes the previous play`() {
        val ontoSilent = GroupPlayLog.events(
            GroupPlayLog.slotKey(1, 0, "a|"),
            GroupPlayLog.slotKey(1, 1, "b|"),
            openLogs = true,
            nextLogs = false
        )
        assertEquals(listOf(GroupPlayLog.Event.END), ontoSilent)

        val staySilent = GroupPlayLog.events(
            null,
            GroupPlayLog.slotKey(1, 0, "b|"),
            openLogs = false,
            nextLogs = false
        )
        assertTrue(staySilent.isEmpty())
    }

    @Test
    fun `an idle schedule closes the open play and does not open another`() {
        assertEquals(listOf(GroupPlayLog.Event.END), GroupPlayLog.idle("5:0:clip|", openLogs = true))
        assertTrue(GroupPlayLog.idle(null, openLogs = true).isEmpty())
        assertTrue(GroupPlayLog.idle("5:0:clip|", openLogs = false).isEmpty())
    }

    @Test
    fun `a single 30s item logs once per cycle, not once per 4Hz tick`() {
        // The production playlist's current item is a 30s video. Group sync loops it and the
        // index never moves, so a log that only fires when the index changes stays at zero
        // forever. Walking the real slot math at the tick rate must yield one play per pass.
        val items = listOf(WallZones.SlotItem(durationSec = 30, allowed = true, isLive = false))
        val periodMs = 30_000L
        val start = periodMs * 100 // a cycle boundary, so three periods are exactly three cycles
        var open: String? = null
        var starts = 0
        var ends = 0
        var ticks = 0
        var t = start
        while (t < start + periodMs * 3) {
            val target = WallZones.target(items, t)!!
            assertEquals("a one-item timeline never changes index", 0, target.index)
            val key = GroupPlayLog.slotKey(target.cycle, target.index, "54769|")
            val ev = GroupPlayLog.events(open, key, openLogs = open != null, nextLogs = true)
            if (open != key) open = key
            for (e in ev) when (e) {
                GroupPlayLog.Event.START -> starts++
                GroupPlayLog.Event.END -> ends++
            }
            ticks++
            t += 250 // GroupScheduleController's 4Hz tick
        }
        assertEquals(3, starts)
        assertEquals(2, ends)
        assertTrue("the tick really did run much faster than the slot", ticks > 100)
    }
}

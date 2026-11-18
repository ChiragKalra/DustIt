package com.bruhascended.dustit.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanActionTest {
    private val running = HeldScan(running = true, attempts = 1)
    private val queued = HeldScan(running = false, attempts = 0)
    private val interrupted = HeldScan(running = false, attempts = 10)

    @Test
    fun `with nothing held every request starts a scan`() {
        for (reason in ScanReason.entries) assertEquals(ScanAction.Start, scanAction(reason, emptyList()))
    }

    @Test
    fun `opening the app leaves a running or freshly queued scan alone`() {
        assertEquals(ScanAction.Nothing, scanAction(ScanReason.Open, listOf(running)))
        assertEquals(ScanAction.Nothing, scanAction(ScanReason.Open, listOf(queued)))
        assertEquals(ScanAction.Nothing, scanAction(ScanReason.Open, listOf(running, queued)))
    }

    @Test
    fun `a change queues one scan behind the running one, never more`() {
        assertEquals(ScanAction.QueueAfterRunning, scanAction(ScanReason.Changed, listOf(running)))
        assertEquals(ScanAction.Nothing, scanAction(ScanReason.Changed, listOf(running, queued)))
        assertEquals(ScanAction.Nothing, scanAction(ScanReason.Changed, listOf(queued)))
    }

    @Test
    fun `an interrupted scan is replaced, with whatever piled up behind it`() {
        val piledUp = listOf(interrupted) + List(325) { queued }
        for (reason in ScanReason.entries) {
            assertEquals(ScanAction.Start, scanAction(reason, listOf(interrupted)))
            assertEquals(ScanAction.Start, scanAction(reason, piledUp))
        }
    }

    @Test
    fun `restart replaces even a running scan`() {
        assertEquals(ScanAction.Start, scanAction(ScanReason.Restart, listOf(running, queued)))
    }
}

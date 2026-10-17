package cn.riyousuode.app

import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private fun base(kind: String = "daily", days: List<Int> = (1..7).toList()): Engine = Engine(AppState(tasks = mutableListOf(Task(id = "t", title = "阅读", start = "2026-10-01", kind = kind, weekdays = days, points = 10, loss = 5, penalty = "整理书桌"))))
    @Test fun missedDailyDaysFailAndChargeOnlyOnce() {
        val e = base(); e.materialize("2026-10-07"); e.materialize("2026-10-07")
        assertEquals(7, e.state.entries.size); assertEquals(-30L, e.state.balance)
        assertEquals(6, e.state.entries.count { it.status == "failed" }); assertEquals("pending", e.state.entries.last().status)
        assertEquals(6, e.state.effects.size); Backup.validate(e.state)
    }
    @Test fun repeatedCompletionAndReversalAreIdempotent() {
        val e = base(); e.materialize("2026-10-01")
        repeat(3) { e.settle("t@2026-10-01", "done", "2026-10-01") }
        assertEquals(10L, e.state.balance); assertEquals(1, e.state.ledger.size)
        e.settle("t@2026-10-01", "pending", "2026-10-01")
        assertEquals(0L, e.state.balance); Backup.validate(e.state)
    }
    @Test fun failThenBackfillUsesNetDifferenceAndRevokesPenalty() {
        val e = base(); e.materialize("2026-10-01")
        e.settle("t@2026-10-01", "failed", "2026-10-02"); assertEquals(-5L, e.state.balance)
        e.settle("t@2026-10-01", "done", "2026-10-02")
        assertEquals(10L, e.state.balance); assertEquals("cancelled", e.state.effects.single().status)
        assertEquals("2026-10-01", e.state.entries.single().date)
    }
    @Test fun correctionPreservesExecutedPenalty() {
        val e = base(); e.materialize("2026-10-01"); e.settle("t@2026-10-01", "failed", "2026-10-02")
        e.finishEffect(e.state.effects.single().id); e.settle("t@2026-10-01", "waived", "2026-10-02")
        assertEquals(0L, e.state.balance); assertEquals("done", e.state.effects.single().status); assertTrue(e.state.effects.single().corrected)
    }
    @Test fun redemptionCannotOverdrawAndCanRefundOnlyOnce() {
        val e = base(); e.materialize("2026-10-01"); e.saveReward(Reward("r", "休息", 10))
        assertThrows(IllegalArgumentException::class.java) { e.redeem("r", "request", "2026-10-01") }
        e.settle("t@2026-10-01", "done", "2026-10-01")
        repeat(3) { e.redeem("r", "request", "2026-10-01") }; assertEquals(0L, e.state.balance)
        e.cancelRedemption(e.state.effects.single().id, "2026-10-01"); assertEquals(10L, e.state.balance)
        assertThrows(IllegalArgumentException::class.java) { e.cancelRedemption(e.state.effects.single().id, "2026-10-01") }
    }
    @Test fun editsDoNotRewriteHistoricalRules() {
        val e = base(); e.materialize("2026-10-01"); e.saveTask(e.state.tasks.single().copy(points = 30), "2026-10-01")
        e.materialize("2026-10-02"); assertEquals(10, e.state.entries.first().points); assertEquals(30, e.state.entries.last().points)
    }
    @Test fun archiveFutureAndOneOffTasks() {
        val e = base("once"); e.materialize("2026-10-07"); assertEquals(1, e.state.entries.size)
        val f = Engine(AppState(tasks = mutableListOf(Task("t", "未来", start = "2026-11-01", kind = "once"))))
        f.archiveTask("t", "2026-10-07"); f.materialize("2026-11-02"); assertTrue(f.state.entries.isEmpty()); Backup.validate(f.state)
    }
    @Test fun changingRecurrenceDoesNotInventMissedPastTasks() {
        val e = base("weekly", listOf(1)); e.materialize("2026-10-07")
        assertEquals(listOf("2026-10-05"), e.state.entries.map { it.date })
        e.saveTask(e.state.tasks.single().copy(kind = "daily"), "2026-10-07")
        assertEquals(1, e.state.entries.size)
        e.materialize("2026-10-08"); assertEquals(listOf("2026-10-05", "2026-10-08"), e.state.entries.map { it.date })
    }
    @Test fun backupRoundTripAndCorruptionRejection() {
        val e = base(); e.materialize("2026-10-03"); e.settle("t@2026-10-01", "done", "2026-10-03")
        val encoded = Backup.encode(e.state); assertEquals(e.state, Backup.decode(encoded))
        assertThrows(Exception::class.java) { Backup.decode("{broken}") }
        assertThrows(Exception::class.java) { Backup.decode("{}") }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(encoded.replace("\"version\": 3", "\"version\": 99")) }
        e.state.entries.add(e.state.entries.first()); assertThrows(IllegalArgumentException::class.java) { Backup.validate(e.state) }
    }
    @Test fun sameDayFailureIsRejectedAndMaxPointCorrectionWorks() {
        val e = base(); e.saveTask(e.state.tasks.single().copy(points = 100000, loss = 100000), "2026-09-30"); e.materialize("2026-10-01")
        assertThrows(IllegalArgumentException::class.java) { e.settle("t@2026-10-01", "failed", "2026-10-01") }
        e.settle("t@2026-10-01", "done", "2026-10-01"); e.settle("t@2026-10-01", "failed", "2026-10-02")
        assertEquals(-100000L, e.state.balance); Backup.validate(e.state)
    }
}

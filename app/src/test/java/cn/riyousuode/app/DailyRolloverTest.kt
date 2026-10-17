package cn.riyousuode.app

import org.junit.Assert.*
import org.junit.Test

class DailyRolloverTest {
    private fun engine(kind: String="daily") = Engine(AppState(tasks=mutableListOf(Task(id="t",title="读书",start="2026-10-15",kind=kind,points=10,loss=5,penalty="家务"))))
    @Test fun currentDayPendingThenFailureAndNewIndependentDay() {
        val e=engine();e.materialize("2026-10-15")
        assertEquals("pending",e.state.entries.single().status);assertEquals(0L,e.state.balance)
        e.materialize("2026-10-16")
        assertEquals("failed",e.state.entries.first().status);assertEquals("pending",e.state.entries.last().status)
        assertEquals(-5L,e.state.balance);assertEquals("pending",e.state.effects.single().status)
        val snapshot=Backup.encode(e.state);repeat(3) { e.materialize("2026-10-16") };assertEquals(snapshot,Backup.encode(e.state))
        e.settle("t@2026-10-16","done","2026-10-16");assertEquals("failed",e.state.entries.first().status);assertEquals(5L,e.state.balance)
        Backup.validate(e.state)
    }
    @Test fun backfillReversesAutomaticFailureAndUnfulfilledPenalty() {
        val e=engine();e.materialize("2026-10-16")
        e.settle("t@2026-10-15","done","2026-10-16")
        assertEquals(10L,e.state.balance);assertEquals("cancelled",e.state.effects.single().status)
        assertEquals(listOf(-5L,15L),e.state.ledger.map { it.amount })
        e.materialize("2026-10-16");assertEquals("done",e.state.entries.first().status)
        assertThrows(IllegalArgumentException::class.java) { e.settle("t@2026-10-15","pending","2026-10-16") }
        Backup.validate(e.state)
    }
    @Test fun realizedPenaltySurvivesBackfillAndWaiverSurvivesRefresh() {
        val e=engine();e.materialize("2026-10-16");e.finishEffect(e.state.effects.single().id)
        e.settle("t@2026-10-15","done","2026-10-16")
        assertEquals("done",e.state.effects.single().status);assertTrue(e.state.effects.single().corrected)
        e.settle("t@2026-10-15","waived","2026-10-16");e.materialize("2026-10-16")
        assertEquals("waived",e.state.entries.first().status);assertEquals(0L,e.state.balance)
    }
    @Test fun oneOffAndWeeklyTasksKeepPendingWithoutDuplicatesOrCharges() {
        for(kind in listOf("once","weekly")) {
            val e=engine(kind);e.materialize("2026-10-20");e.materialize("2026-10-20")
            assertTrue(e.state.entries.all { it.status=="pending" });assertEquals(0L,e.state.balance);assertTrue(e.state.effects.isEmpty())
            if(kind=="once") assertEquals(1,e.state.entries.size)
            e.settle("t@2026-10-15","done","2026-10-20");assertEquals(10L,e.state.balance);Backup.validate(e.state)
        }
    }
    @Test fun recurrenceChangesPreserveEachEntryOriginalRule() {
        val once=engine("once");once.materialize("2026-10-15")
        once.saveTask(once.state.tasks.single().copy(kind="daily"),"2026-10-16")
        assertEquals("once",once.state.entries.first().kind);assertEquals("pending",once.state.entries.first().status)
        once.materialize("2026-10-18");assertEquals("failed",once.state.entries.first { it.date=="2026-10-17" }.status)
        val daily=engine();daily.materialize("2026-10-15");daily.saveTask(daily.state.tasks.single().copy(kind="once"),"2026-10-15")
        daily.materialize("2026-10-16");assertEquals("failed",daily.state.entries.single().status)
    }
    @Test fun legacyPendingEntriesAreClassifiedAndAutomaticallyFailed() {
        val e=engine();e.state.entries.add(Entry("t@2026-10-15","t","2026-10-15","旧每日",10,5,"家务"))
        e.state.tasks[0]=e.state.tasks[0].copy(generatedThrough="2026-10-15")
        val restored=Engine(Backup.decode(Backup.encode(e.state)));restored.materialize("2026-10-16")
        assertEquals("daily",restored.state.entries.first().kind);assertEquals("failed",restored.state.entries.first().status)
        assertEquals(-5L,restored.state.balance);Backup.validate(restored.state)
    }
    @Test fun archivedDailyExpiresButFutureAndCompletedDaysAreNotFailed() {
        val e=engine();e.materialize("2026-10-15");e.archiveTask("t","2026-10-15");e.materialize("2026-10-16")
        assertEquals(1,e.state.entries.size);assertEquals("failed",e.state.entries.single().status)
        val completed=engine();completed.materialize("2026-10-15");completed.settle("t@2026-10-15","done","2026-10-15")
        completed.materialize("2026-10-16");assertEquals("done",completed.state.entries.first().status)
        val future=engine();future.materialize("2026-10-14");assertTrue(future.state.entries.isEmpty())
    }
    @Test fun missedMonthsUseSnapshotsAndDeletionRefundsNetAutomaticCharges() {
        val e=engine();e.materialize("2026-10-15");e.saveTask(e.state.tasks.single().copy(loss=20,penalty="运动"),"2026-10-15")
        e.materialize("2026-11-01")
        assertEquals(-325L,e.state.balance);assertEquals("家务",e.state.effects.first().title)
        assertEquals(17,e.state.entries.count { it.status=="failed" })
        val snapshot=Backup.encode(e.state);e.materialize("2026-11-01");assertEquals(snapshot,Backup.encode(e.state))
        e.deleteTask("t","2026-11-01");assertEquals(0L,e.state.balance);assertTrue(e.state.effects.all { it.status=="cancelled" });Backup.validate(e.state)
    }
}

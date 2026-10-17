package cn.riyousuode.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class UpgradeTest {
    private fun task() = Engine(AppState(tasks = mutableListOf(Task("t", "读书", start = "2026-10-01", kind = "weekly", points = 10, loss = 5, penalty = "整理书桌")))).apply { materialize("2026-10-03") }
    @Test fun deletionReversesNetPointsAndPreservesSpentRewards() {
        val e = task()
        e.settle("t@2026-10-01", "done", "2026-10-03")
        e.settle("t@2026-10-02", "failed", "2026-10-03")
        e.finishEffect(e.state.effects.single().id)
        e.state.goals.add(Goal("g", "阅读目标", taskId = "t", target = 1, start = "2026-10-01", reward = "一本书"))
        e.state.effects.add(Effect(source = "goal:g:reward", title = "一本书", kind = "reward", date = "2026-10-03"))
        e.saveReward(Reward("r", "休息", 5)); e.redeem("r", "purchase", "2026-10-03")
        val purchase = e.state.effects.first { it.source == "redeem:purchase" }
        val impact = e.deletionImpact("t")
        assertEquals(3, impact.entries); assertEquals(listOf("阅读目标"), impact.goals)
        assertEquals(-5L, impact.adjustment); assertEquals(1, impact.pendingEffects); assertEquals(1, impact.doneEffects)
        e.deleteTask("t", "2026-10-03")
        assertTrue(e.state.tasks.isEmpty()); assertTrue(e.state.entries.isEmpty()); assertTrue(e.state.goals.isEmpty())
        assertEquals(-5L, e.state.balance)
        val penalty = e.state.effects.first { it.kind == "penalty" }
        assertTrue(penalty.sourceDeleted && penalty.corrected); assertEquals("done", penalty.status)
        assertEquals("cancelled", e.state.effects.first { it.source == "goal:g:reward" }.status)
        assertEquals(purchase, e.state.effects.first { it.id == purchase.id })
        val snapshot = Backup.encode(e.state)
        e.deleteTask("t", "2026-10-03"); e.materialize("2026-10-10"); e.syncGoals("2026-10-10")
        assertEquals(snapshot, Backup.encode(e.state)); Backup.validate(e.state)
    }
    @Test fun deletingFailedTaskRefundsPenaltyAndDoesNotTouchOtherTasks() {
        val e = task(); e.settle("t@2026-10-01", "failed", "2026-10-03")
        e.saveTask(Task("other", "另一项", start = "2026-10-03"), "2026-10-03")
        e.settle("other@2026-10-03", "done", "2026-10-03")
        e.deleteTask("t", "2026-10-03")
        assertEquals(10L, e.state.balance); assertEquals("other", e.state.tasks.single().id)
        assertEquals(5L, e.state.ledger.last().amount); Backup.validate(e.state)
    }
    @Test fun deletingFutureAndArchivedTasksStopsGeneration() {
        val e = Engine(AppState(tasks = mutableListOf(Task("future", "未来", start = "2026-11-01"))))
        e.deleteTask("future", "2026-10-03"); assertTrue(e.state.ledger.isEmpty())
        val past = task(); past.archiveTask("t", "2026-10-03"); past.deleteTask("t", "2026-10-03")
        past.materialize("2026-12-01"); assertTrue(past.state.entries.isEmpty()); Backup.validate(past.state)
    }
    @Test fun deletionHandlesPointTotalsLargerThanAnInt() {
        val e = Engine(AppState(tasks = mutableListOf(Task("t", "长期任务", start = "2000-01-01"))))
        repeat(22000) { n -> val day = java.time.LocalDate.of(2000, 1, 1).plusDays(n.toLong()).toString(); val id = "t@$day"; e.state.entries.add(Entry(id, "t", day, "长期任务", 100000, 0, "", "done")); e.state.ledger.add(Ledger(source = id, title = "完成", amount = 100000L, date = day)) }
        assertEquals(-2_200_000_000L, e.deletionImpact("t").adjustment)
        e.deleteTask("t", "2061-01-01"); assertEquals(0L, e.state.balance); Backup.validate(e.state)
    }
    @Test fun centsAreExactAndInvalidInputsAreRejected() {
        assertEquals(1L, Money.parse("0.01")); assertEquals(1230L, Money.parse("12.3"))
        assertEquals("0.30", Money.format(Money.parse("0.1") + Money.parse("0.2")))
        listOf("0", "-1", "1.001", "NaN", "1e3", "", "1,000", "100000000.00", "1.").forEach { value -> assertThrows(IllegalArgumentException::class.java) { Money.parse(value) } }
    }
    @Test fun moneyCrudIsIndependentAndMonthlyTotalsAreExact() {
        val e = task(); e.settle("t@2026-10-01", "done", "2026-10-03")
        e.saveMoney(MoneyRecord("a", "expense", 101, "餐饮", "2026-10-01", "早餐"))
        e.saveMoney(MoneyRecord("b", "expense", 202, "交通", "2026-10-02"))
        e.saveMoney(MoneyRecord("c", "income", 1000, "工资", "2026-10-03"))
        e.saveMoney(MoneyRecord("d", "expense", 500, "餐饮", "2026-09-30"))
        val month = YearMonth.of(2026, 10)
        assertEquals(MonthSummary(1000, 303), Money.summary(e.state.moneyRecords, month))
        assertEquals(697L, Money.summary(e.state.moneyRecords, month).net)
        assertEquals(listOf("c", "b", "a"), Money.records(e.state.moneyRecords, month).map { it.id })
        assertEquals(2, Money.records(e.state.moneyRecords, month, "expense").size)
        val breakdown = Money.breakdown(e.state.moneyRecords, month, "expense")
        assertEquals("交通", breakdown.first().category); assertEquals(1f, breakdown.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
        e.saveMoney(e.state.moneyRecords.first().copy(amountCents = 400, date = "2026-09-29"))
        assertEquals(202L, Money.summary(e.state.moneyRecords, month).expense)
        e.deleteMoney("b"); e.deleteMoney("b"); assertEquals(0L, Money.summary(e.state.moneyRecords, month).expense)
        assertEquals(10L, e.state.balance); Backup.validate(e.state)
    }
    @Test fun categoriesAndDatesValidatedBeforeMutation() {
        val e = Engine(AppState())
        assertThrows(Exception::class.java) { e.saveMoney(MoneyRecord(type = "expense", amountCents = 100, category = "工资")) }
        assertThrows(Exception::class.java) { e.saveMoney(MoneyRecord(type = "expense", amountCents = 0, category = "餐饮")) }
        assertThrows(Exception::class.java) { e.saveMoney(MoneyRecord(type = "expense", amountCents = 100, category = "餐饮", date = "2026-02-30")) }
        assertTrue(e.state.moneyRecords.isEmpty())
    }
    @Test fun v1MigratesWithoutLosingHistoricalRules() {
        val e = task(); e.settle("t@2026-10-01", "failed", "2026-10-03")
        val root = Backup.json.parseToJsonElement(Backup.encode(e.state)).jsonObject.toMutableMap()
        root["version"] = JsonPrimitive(1); root.remove("moneyRecords"); root.remove("countdowns")
        val imported = Backup.decode(JsonObject(root).toString())
        assertEquals(3, imported.version); assertTrue(imported.moneyRecords.isEmpty())
        assertEquals(e.state, imported)
        assertEquals(imported, Backup.decode(Backup.encode(imported)))
    }
    @Test fun v2RoundTripIncludesMoneyAndRejectsMissingOrInvalidRecords() {
        val s = AppState(moneyRecords = mutableListOf(MoneyRecord(type = "income", amountCents = 12345, category = "红包")))
        assertEquals(s, Backup.decode(Backup.encode(s)))
        val root = Backup.json.parseToJsonElement(Backup.encode(s)).jsonObject.toMutableMap()
        root.remove("moneyRecords")
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(JsonObject(root).toString()) }
        s.moneyRecords.add(s.moneyRecords.first()); assertThrows(IllegalArgumentException::class.java) { Backup.validate(s) }
    }
}

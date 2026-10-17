package cn.riyousuode.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CountdownTest {
    @Test fun calendarDaysHandleTodayPastLeapDayAndYearChange() {
        val now=LocalDate.of(2028,2,28)
        assertEquals("还有 2 天",Countdowns.label(Countdown(title="闰年",date="2028-03-01"),now))
        assertEquals("就是今天",Countdowns.label(Countdown(title="今天",date="2028-02-28"),now))
        assertEquals("已过去 1 天",Countdowns.label(Countdown(title="昨天",date="2028-02-27"),now))
        assertEquals(1L,Countdowns.days(Countdown(title="跨年",date="2027-01-01"),LocalDate.of(2026,12,31)))
        assertEquals("就是今天",Countdowns.label(Countdown(title="跨午夜",date="2027-01-01"),LocalDate.of(2027,1,1)))
    }
    @Test fun upcomingSortedAscendingThenPastDescending() {
        val dates=listOf("2026-10-01","2026-10-10","2026-10-07","2026-10-06","2026-10-08")
        val sorted=Countdowns.sorted(dates.map { Countdown(id=it,title=it,date=it) },LocalDate.of(2026,10,7))
        assertEquals(listOf("2026-10-07","2026-10-08","2026-10-10","2026-10-06","2026-10-01"),sorted.map { it.date })
    }
    @Test fun crudAndLimitDoNotTouchFinancialRecords() {
        val e=Engine(AppState(moneyRecords=mutableListOf(MoneyRecord(type="income",amountCents=123,category="工资")),ledger=mutableListOf(Ledger(source="old",title="历史",amount=10))))
        repeat(100) { e.saveCountdown(Countdown(id="$it",title="事项$it",date="2026-10-07")) }
        assertThrows(IllegalArgumentException::class.java) { e.saveCountdown(Countdown(title="超限",date="2026-10-07")) }
        e.saveCountdown(e.state.countdowns.first().copy(title="改名")); assertEquals("改名",e.state.countdowns.first().title)
        e.deleteCountdown("0");e.deleteCountdown("0");assertEquals(99,e.state.countdowns.size)
        assertEquals(10L,e.state.balance);assertEquals(123L,e.state.moneyRecords.single().amountCents)
        assertEquals(e.state,Backup.decode(Backup.encode(e.state)))
    }
    @Test fun invalidRecordsRejected() {
        val c=Countdown(title="有效",date="2026-10-07")
        listOf(c.copy(title=" "),c.copy(date="2026-02-30"),c.copy(background="invalid"),c.copy(photo="../x.jpg"),c.copy(date="2101-01-01")).forEach { bad -> assertThrows(Exception::class.java) { Countdowns.validate(bad) } }
        val s=AppState(countdowns=mutableListOf(c,c));assertThrows(Exception::class.java) { Backup.validate(s) }
    }
    @Test fun oldGoalsNeverRecomputeAndPendingRewardsRemainRedeemable() {
        val task=Task("t","任务",start="2026-10-01")
        val goal=Goal("g","旧目标",taskId="t",target=1,start="2026-10-01",reward="休息")
        val reward=Effect(source="goal:g:reward",title="休息",kind="reward")
        val e=Engine(AppState(tasks=mutableListOf(task),goals=mutableListOf(goal),effects=mutableListOf(reward)))
        e.materialize("2026-10-01");e.settle("t@2026-10-01","done","2026-10-01");e.syncGoals("2026-10-02")
        assertEquals(goal,e.state.goals.single());assertEquals(reward,e.state.effects.single())
        e.finishEffect(reward.id);assertEquals("done",e.state.effects.single().status)
        e.settle("t@2026-10-01","pending","2026-10-01");assertFalse(e.state.effects.single().corrected)
    }
    @Test fun v1AndV2MigrationDoesNotGenerateOldGoalRewards() {
        val state=AppState(goals=mutableListOf(Goal(title="手动旧目标",type="manual",reward="旅行")),moneyRecords=mutableListOf(MoneyRecord(type="income",amountCents=100,category="工资")))
        for(version in 1..2) {
            val root=Backup.json.parseToJsonElement(Backup.encode(state)).jsonObject.toMutableMap()
            root["version"]=JsonPrimitive(version);root.remove("countdowns");if(version==1)root.remove("moneyRecords")
            val migrated=Backup.decode(JsonObject(root).toString());val e=Engine(migrated);e.materialize();e.syncGoals()
            assertEquals(3,migrated.version);assertTrue(migrated.countdowns.isEmpty());assertTrue(migrated.effects.isEmpty())
            assertEquals(state.goals,migrated.goals);assertEquals(if(version==1)0 else 1,migrated.moneyRecords.size)
        }
    }
}

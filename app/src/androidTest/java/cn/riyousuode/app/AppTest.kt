package cn.riyousuode.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as DayApplication
    @Before fun reset() {
        runBlocking { app.store.restore(Backup.encode(AppState())) }
        rule.waitForIdle()
    }
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(350)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            rule.activity.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun taskCreationCompletionAndRewardRedemption() {
        rule.onNodeWithText("添加任务").performClick()
        rule.onNodeWithText("任务名称").performTextInput("阅读二十分钟")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value?.tasks?.size == 1 }
        rule.onNodeWithContentDescription("完成 阅读二十分钟").performClick()
        rule.waitUntil(10000) { app.store.state.value?.balance == 10L }
        rule.onNodeWithText("奖惩").performClick()
        rule.onNodeWithText("添加奖励").performClick()
        rule.onNodeWithText("想给自己的奖励").performTextInput("一杯好茶")
        rule.onNodeWithText("兑换所需积分").performTextReplacement("10")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value?.rewards?.size == 1 }
        rule.onNodeWithText("兑换", useUnmergedTree = true).performClick()
        rule.onNodeWithText("确认").performClick()
        rule.waitUntil(10000) { app.store.state.value?.balance == 0L }
        Assert.assertEquals(1, app.store.state.value!!.effects.size)
    }
    @Test fun persistentBackupRestoreAndInvalidFileKeepsData() {
        runBlocking {
            app.store.change { saveTask(Task(title = "运动")); settle(state.entries.single().id, "done") }
            val backup = app.store.export()
            val reopened = Store(app); reopened.refresh()
            Assert.assertEquals(10L, reopened.state.value!!.balance)
            try { app.store.restore("bad json"); Assert.fail() } catch(_: Exception) { }
            Assert.assertEquals(10L, app.store.state.value!!.balance)
            app.store.restore(Backup.encode(AppState())); Assert.assertEquals(0L, app.store.state.value!!.balance)
            app.store.restore(backup); Assert.assertEquals(10L, app.store.state.value!!.balance)
        }
    }
    @Test fun seededScreensAndMissedTaskSettlement() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        runBlocking { app.store.change {
            saveTask(Task(id = "read", title = "读书 20 分钟", note = "睡前读几页", start = yesterday, points = 15, loss = 5, penalty = "整理书桌"))
            saveTask(Task(id = "walk", title = "出门走一走", points = 10))
            saveTask(Task(id = "sleep", title = "23 点前放下手机", points = 20))
            settle("read@$yesterday", "done")
            settle("walk@${today()}", "done")
            state.goals.add(Goal(title = "让阅读成为习惯", type = "streak", taskId = "read", target = 7, start = yesterday, reward = "买一本期待已久的书", penalty = "整理一次书架"))
            saveCountdown(Countdown(title="一起去看湖泊",date=LocalDate.now().plusDays(28).toString()))
            saveCountdown(Countdown(title="去山里走走",date=LocalDate.now().plusDays(60).toString(),background="mountain"))
            saveCountdown(Countdown(title="值得记住的日落",date=LocalDate.now().minusDays(12).toString(),background="sunset"))
            saveReward(Reward(title = "一杯喜欢的咖啡", cost = 30)); saveReward(Reward(title = "看一场电影", cost = 100))
        } }
        rule.waitForIdle(); shot("01-today")
        rule.onNodeWithText("倒计时").performClick(); shot("02-countdowns")
        rule.onNodeWithText("奖惩").performClick(); shot("03-rewards")
        runBlocking { app.store.change {
            saveMoney(MoneyRecord(type = "income", amountCents = 650000, category = "工资", note = "本月工资"))
            saveMoney(MoneyRecord(type = "expense", amountCents = 2850, category = "餐饮", note = "午餐"))
            saveMoney(MoneyRecord(type = "expense", amountCents = 600, category = "交通", note = "地铁"))
        } }
        rule.onNodeWithText("记账").performClick(); shot("05-money")
        rule.onNodeWithText("我的").performClick(); shot("04-profile")
    }
    @Test fun deleteFromManagementRemovesHistoryGoalsAndReminder() {
        runBlocking { app.store.change {
            saveTask(Task(id = "delete-me", title = "待删除任务", reminder = "23:59"))
            settle("delete-me@${today()}", "done")
            state.goals.add(Goal(id = "delete-goal", title = "关联目标", taskId = "delete-me", target = 1, reward = "休息"))
        } }
        Reminders.schedule(app, app.store.state.value!!)
        rule.onNodeWithText("管理").performClick()
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.onNodeWithText("确认删除").assertIsDisplayed()
        shot("06-delete-confirmation")
        rule.onNodeWithText("确认删除").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.tasks.isEmpty() }
        Assert.assertTrue(app.store.state.value!!.entries.isEmpty())
        Assert.assertTrue(app.store.state.value!!.goals.isEmpty())
        Assert.assertEquals(0L, app.store.state.value!!.balance)
        rule.waitUntil(10000) { app.getSharedPreferences("reminders", 0).getStringSet("scheduled", emptySet())!!.isEmpty() }
    }
    @Test fun moneyUiCreateEditDeleteAndFilters() {
        rule.onNodeWithText("记账").performClick()
        rule.onNodeWithContentDescription("新增收支").performClick()
        rule.onNodeWithText("金额（元）").performTextInput("12.34")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.moneyRecords.size == 1 }
        Assert.assertEquals(1234L, app.store.state.value!!.moneyRecords.single().amountCents)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("编辑"))
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.onNodeWithText("金额（元）").performTextReplacement("15.60")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.moneyRecords.single().amountCents == 1560L }
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.onNodeWithText("确认").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.moneyRecords.isEmpty() }
        Assert.assertEquals(0L, app.store.state.value!!.balance)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithContentDescription("上个月").performClick()
        rule.onNodeWithText("回到本月").assertIsDisplayed().performClick()
    }
    @Test fun v1RestoreClearsMoneyButKeepsLegacyAndFailureRollsBack() {
        val legacy = """{"version":1,"tasks":[],"entries":[],"goals":[],"rewards":[],"ledger":[],"effects":[]}"""
        runBlocking {
            app.store.change { saveMoney(MoneyRecord(type = "expense", amountCents = 123, category = "餐饮")) }
            val v2 = app.store.export()
            try { app.store.change { deleteMoney(state.moneyRecords.single().id); error("transaction failure") }; Assert.fail() } catch(_: IllegalStateException) { }
            Assert.assertEquals(1, app.store.state.value!!.moneyRecords.size)
            Assert.assertEquals(v2, app.store.export())
            app.store.restore(legacy)
            Assert.assertEquals(3, app.store.state.value!!.version)
            Assert.assertTrue(app.store.state.value!!.moneyRecords.isEmpty())
            app.store.restore(v2)
            Assert.assertEquals(123L, app.store.state.value!!.moneyRecords.single().amountCents)
        }
    }
    @Test fun missedTaskDialogAndNegativeBalance() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        runBlocking { app.store.change { saveTask(Task(id = "missed", title = "昨天的任务", start = yesterday, kind = "once", loss = 5, penalty = "做家务")) } }
        rule.waitForIdle()
        rule.onNodeWithText("昨天的任务").performClick()
        rule.onNodeWithText("确认未完成").performClick()
        rule.waitUntil(10000) { app.store.state.value?.balance == -5L }
        Assert.assertEquals("pending", app.store.state.value!!.effects.single().status)
    }
    @Test fun dailyRolloverMovesFailureToHistoryWhileOneOffCarriesForward() {
        val yesterday=LocalDate.now().minusDays(1).toString()
        val daily=Task(id="daily-roll",title="今天的每日",start=yesterday,loss=5,penalty="整理书桌",generatedThrough=yesterday)
        val once=Task(id="once-roll",title="顺延的一次性",start=yesterday,kind="once",generatedThrough=yesterday)
        val legacy=AppState(tasks=mutableListOf(daily,once),entries=mutableListOf(
            Entry("daily-roll@$yesterday","daily-roll",yesterday,"昨天的每日",10,5,"整理书桌"),
            Entry("once-roll@$yesterday","once-roll",yesterday,"顺延的一次性",10,0,"")
        ))
        runBlocking { app.store.restore(Backup.encode(legacy));repeat(3) { app.store.refresh() } }
        Assert.assertEquals(-5L,app.store.state.value!!.balance)
        Assert.assertEquals(1,app.store.state.value!!.ledger.size)
        rule.onNodeWithText("昨天的每日").assertDoesNotExist()
        rule.onNodeWithText("顺延的一次性").assertExists()
        rule.onNodeWithText("我的").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("任务历史"))
        rule.onNodeWithText("任务历史").performScrollTo().performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("昨天的每日"))
        rule.onNodeWithText("$yesterday · 未完成 · +10 积分").assertExists()
        shot("11-daily-failed-history")
        rule.onNodeWithText("昨天的每日").performScrollTo().performClick()
        rule.onNodeWithText("撤销 / 恢复待完成").assertDoesNotExist()
        rule.onNodeWithText("补记完成").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.entries.first { it.id=="daily-roll@$yesterday" }.status=="done" }
        Assert.assertEquals(10L,app.store.state.value!!.balance)
        Assert.assertEquals("cancelled",app.store.state.value!!.effects.single().status)
        rule.onNodeWithText("今日").performClick()
        rule.onNodeWithText("顺延的一次性").performScrollTo().performClick()
        rule.onNodeWithText("补记完成").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.entries.first { it.id=="once-roll@$yesterday" }.status=="done" }
        Assert.assertEquals(20L,app.store.state.value!!.balance)
        Assert.assertEquals("pending",app.store.state.value!!.entries.first { it.id=="daily-roll@${today()}" }.status)
    }
    @Test fun reminderBroadcastShowsLocalNotification() {
        org.junit.Assume.assumeTrue(Reminders.available(app))
        runBlocking { app.store.change { saveTask(Task(id = "reminder", title = "提醒测试", reminder = "23:59", kind = "once")) } }
        Reminders.schedule(app, app.store.state.value!!)
        app.sendBroadcast(android.content.Intent(app, ReminderReceiver::class.java).putExtra("taskId", "reminder").putExtra("date", today()))
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        rule.waitUntil(10000) { manager.activeNotifications.any { it.notification.extras.getString("android.title") == "提醒测试" } }
        manager.cancelAll()
        runBlocking { app.store.change { settle("reminder@${today()}", "done") } }
        Reminders.schedule(app, app.store.state.value!!)
        Assert.assertTrue(app.getSharedPreferences("reminders", 0).getStringSet("scheduled", emptySet())!!.isEmpty())
    }
}



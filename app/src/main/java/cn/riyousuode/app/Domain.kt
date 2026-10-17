package cn.riyousuode.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString()
fun today(): String = LocalDate.now().toString()

@Serializable data class Task(
    val id: String = uid(), val title: String, val note: String = "",
    val start: String = today(), val kind: String = "daily", val weekdays: List<Int> = (1..7).toList(),
    val points: Int = 10, val loss: Int = 0, val penalty: String = "", val reminder: String = "",
    val archived: String? = null, val generatedThrough: String? = null
)
@Serializable data class Entry(
    val id: String, val taskId: String, val date: String, val title: String,
    val points: Int, val loss: Int, val penalty: String, val status: String = "pending",
    val kind: String? = null
)
@Serializable data class Goal(
    val id: String = uid(), val title: String, val type: String = "count", val taskId: String = "",
    val target: Int = 7, val start: String = today(), val deadline: String? = null,
    val reward: String = "", val penalty: String = "", val outcome: String = "active",
    val archived: Boolean = false, val archivedOn: String? = null
)
@Serializable data class Reward(val id: String = uid(), val title: String, val cost: Int = 50, val archived: Boolean = false)
@Serializable data class Ledger(val id: String = uid(), val source: String, val title: String, val amount: Long, val date: String = today())
@Serializable data class Effect(
    val id: String = uid(), val source: String, val title: String, val kind: String,
    val date: String = today(), val status: String = "pending", val corrected: Boolean = false,
    val sourceDeleted: Boolean = false
)
@Serializable data class AppState(
    val version: Int = 3,
    val tasks: MutableList<Task> = mutableListOf(), val entries: MutableList<Entry> = mutableListOf(),
    val goals: MutableList<Goal> = mutableListOf(), val rewards: MutableList<Reward> = mutableListOf(),
    val ledger: MutableList<Ledger> = mutableListOf(), val effects: MutableList<Effect> = mutableListOf(),
    val moneyRecords: MutableList<MoneyRecord> = mutableListOf(),
    val countdowns: MutableList<Countdown> = mutableListOf()
) { val balance: Long get() = ledger.sumOf { it.amount.toLong() } }

object Backup {
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(state: AppState) = json.encodeToString(state)
    fun decode(text: String): AppState {
        require(text.toByteArray().size <= 10 * 1024 * 1024) { "备份超过 10 MB，无法导入" }
        val root = json.parseToJsonElement(text).jsonObject
        require(root.keys.containsAll(listOf("version", "tasks", "entries", "goals", "rewards", "ledger", "effects"))) { "备份缺少必要的数据字段" }
        val version = root["version"]?.jsonPrimitive?.intOrNull
        require(version in 1..3) { "不支持此备份版本" }
        require(version == 1 || "moneyRecords" in root) { "备份缺少记账数据字段" }
        require(version != 1 || "moneyRecords" !in root) { "旧版备份不能包含新版记账字段" }
        require(version == 3 || "countdowns" !in root) { "旧版备份不能包含倒计时" }
        require(version != 3 || "countdowns" in root) { "备份缺少倒计时" }
        val s = json.decodeFromString<AppState>(text)
        val migrated = s.copy(version = 3)
        validate(migrated)
        return migrated
    }
    fun validate(s: AppState) {
        require(s.version == 3) { "不支持此备份版本" }
        fun date(v: String) { val d = LocalDate.parse(v); require(d.year in 2000..2100) { "日期超出支持范围" } }
        fun ids(v: List<String>) { require(v.all { it.isNotBlank() && it.length <= 120 } && v.distinct().size == v.size) { "备份含重复或无效记录" } }
        fun name(v: String) { require(v.isNotBlank() && v.length <= 100) { "名称须为 1–100 个字" } }
        fun amount(v: Int) { require(v in 0..100000) { "积分须在 0–100000 之间" } }
        require(s.entries.size <= 100000 && s.tasks.size <= 1000 && s.goals.size <= 1000 && s.rewards.size <= 1000 && s.effects.size <= 100000 && s.ledger.size <= 200000) { "记录数量超过上限" }
        require(s.countdowns.size <= 100) { "最多保存 100 项倒计时" }; ids(s.countdowns.map { it.id }); s.countdowns.forEach { Countdowns.validate(it) }
        ids(s.tasks.map { it.id }); ids(s.entries.map { it.id }); ids(s.goals.map { it.id }); ids(s.rewards.map { it.id }); ids(s.ledger.map { it.id }); ids(s.effects.map { it.id })
        require(s.moneyRecords.size <= 100000) { "记账数量超过上限" }
        ids(s.moneyRecords.map { it.id })
        s.moneyRecords.forEach { Money.validate(it) }
        s.tasks.forEach { t ->
            name(t.title); date(t.start); t.archived?.let { date(it) }; amount(t.points); amount(t.loss)
            t.generatedThrough?.let { date(it) }
            require(t.kind in listOf("once", "daily", "weekly")); require(t.weekdays.all { it in 1..7 } && t.weekdays.distinct().size == t.weekdays.size)
            require(t.kind != "weekly" || t.weekdays.isNotEmpty()); require(t.note.length <= 1000 && t.penalty.length <= 1000)
            if(t.reminder.isNotBlank()) LocalTime.parse(t.reminder)
        }
        val taskIds = s.tasks.map { it.id }.toSet()
        s.entries.forEach { e ->
            date(e.date); name(e.title); amount(e.points); amount(e.loss)
            require(e.taskId in taskIds && e.id == "${e.taskId}@${e.date}" && e.penalty.length <= 1000)
            require(e.status in listOf("pending", "done", "waived", "failed"))
            require(e.kind == null || e.kind in listOf("daily", "weekly", "once"))
        }
        s.goals.forEach { g ->
            name(g.title); date(g.start); g.deadline?.let { date(it); require(it >= g.start) { "截止日不能早于开始日" } }
            g.archivedOn?.let { date(it); require(it >= g.start) }
            require(g.type in listOf("count", "streak", "manual") && g.target in 1..100000)
            require(g.type == "manual" || g.taskId in taskIds); require(g.outcome in listOf("active", "success", "failed"))
            require(g.reward.length <= 1000 && g.penalty.length <= 1000)
        }
        s.rewards.forEach { name(it.title); amount(it.cost); require(it.cost > 0) }
        s.ledger.forEach { date(it.date); require(it.amount in -10000000000L..10000000000L && it.title.length <= 1200 && it.source.length <= 200) }
        s.effects.forEach { date(it.date); require(it.kind in listOf("reward", "penalty") && it.status in listOf("pending", "done", "cancelled") && it.title.length <= 1200 && it.source.length <= 200) }
        require(s.effects.map { it.source }.distinct().size == s.effects.size) { "奖惩来源重复" }
        val ledgerTotals = s.ledger.groupBy { it.source }.mapValues { (_, items) -> items.sumOf { it.amount.toLong() } }
        s.entries.forEach { e ->
            val expected = when(e.status) { "done" -> e.points; "failed" -> -e.loss; else -> 0 }
            require((ledgerTotals[e.id] ?: 0L) == expected.toLong()) { "任务与积分账本不一致" }
        }
    }
}

class Engine(val state: AppState) {
    fun deletionImpact(id: String): DeletionImpact {
        val records = state.entries.filter { it.taskId == id }.map { it.id }.toSet()
        val goals = state.goals.filter { it.taskId == id }
        val sources = records.map { "task:$it" }.toSet() + goals.flatMap { listOf("goal:${it.id}:reward", "goal:${it.id}:penalty") }
        val effects = state.effects.filter { it.source in sources }
        return DeletionImpact(records.size, goals.map { it.title }, -state.ledger.filter { it.source in records }.sumOf { it.amount }, effects.count { it.status == "pending" }, effects.count { it.status == "done" })
    }
    fun deleteTask(id: String, now: String = today()) {
        val task = state.tasks.find { it.id == id } ?: return
        val impact = deletionImpact(id)
        val entries = state.entries.filter { it.taskId == id }.map { it.id }.toSet()
        val goals = state.goals.filter { it.taskId == id }.map { it.id }.toSet()
        val sources = entries.map { "task:$it" }.toSet() + goals.flatMap { listOf("goal:$it:reward", "goal:$it:penalty") }
        state.effects.indices.forEach { i ->
            val e = state.effects[i]
            if(e.source in sources) state.effects[i] = e.copy(sourceDeleted = true, corrected = e.status == "done", status = if(e.status == "done") "done" else "cancelled")
        }
        if(impact.adjustment != 0L) state.ledger.add(Ledger(source = "delete:$id", title = "删除任务 · ${task.title} · 回退净积分", amount = impact.adjustment, date = now))
        state.entries.removeAll { it.taskId == id }
        state.goals.removeAll { it.id in goals }
        state.tasks.removeAll { it.id == id }
    }
    fun saveMoney(record: MoneyRecord) {
        Money.validate(record)
        val index = state.moneyRecords.indexOfFirst { it.id == record.id }
        if(index < 0) state.moneyRecords.add(record) else state.moneyRecords[index] = record
    }
    fun deleteMoney(id: String) { state.moneyRecords.removeAll { it.id == id } }
    fun materialize(now: String = today()) {
        val known = state.entries.map { it.id }.toMutableSet()
        state.tasks.toList().forEachIndexed { index, t ->
            var day = maxOf(LocalDate.parse(t.start), t.generatedThrough?.let { LocalDate.parse(it).plusDays(1) } ?: LocalDate.parse(t.start))
            val last = minOf(LocalDate.parse(now), t.archived?.let(LocalDate::parse) ?: LocalDate.parse(now))
            while (!day.isAfter(last)) {
                val ds = day.toString()
                val due = t.kind == "daily" || (t.kind == "once" && ds == t.start) || (t.kind == "weekly" && day.dayOfWeek.value in t.weekdays)
                val id = "${t.id}@$ds"
                if (due && known.add(id)) {
                    require(state.entries.size < 100000) { "历史记录达到上限，请先导出备份" }
                    state.entries.add(Entry(id, t.id, ds, t.title, t.points, t.loss, t.penalty, kind = t.kind))
                }
                day = day.plusDays(1)
            }
            if(last >= LocalDate.parse(t.start) && (t.generatedThrough == null || last.toString() > t.generatedThrough)) state.tasks[index] = t.copy(generatedThrough = last.toString())
        }
        val kinds = state.tasks.associate { it.id to it.kind }
        // Freeze legacy entries before edits can change the task's recurrence.
        state.entries.indices.forEach { i ->
            val e = state.entries[i]
            if(e.kind == null) state.entries[i] = e.copy(kind = kinds[e.taskId])
        }
        val overdue = state.entries.indices.filter { state.entries[it].let { e -> e.kind == "daily" && e.date < now && e.status == "pending" } }
        if(overdue.isNotEmpty()) {
            val totals = state.ledger.groupBy { it.source }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
            val effects = state.effects.mapIndexed { index, e -> e.source to index }.toMap()
            overdue.forEach { index ->
                val id = state.entries[index].id
                transition(index, "failed", now, totals[id] ?: 0L, effects["task:$id"] ?: -1)
            }
        }
    }
    fun saveTask(t: Task, now: String = today()) {
        materialize(now)
        val index = state.tasks.indexOfFirst { it.id == t.id }
        if(index < 0) state.tasks.add(t) else {
            val old = state.tasks[index]
            require(old.start == t.start && old.archived == t.archived) { "已有任务的开始日期不能修改" }
            state.tasks[index] = t.copy(generatedThrough = old.generatedThrough)
        }
        materialize(now)
    }
    fun archiveTask(id: String, now: String = today()) {
        materialize(now)
        val i = state.tasks.indexOfFirst { it.id == id }
        require(i >= 0)
        require(state.goals.none { it.taskId == id && it.outcome == "active" && !it.archived }) { "请先结束或归档关联目标" }
        state.tasks[i] = state.tasks[i].copy(archived = now)
    }
    fun settle(id: String, status: String, now: String = today()) {
        require(status in listOf("pending", "done", "waived", "failed"))
        val i = state.entries.indexOfFirst { it.id == id }; require(i >= 0) { "找不到任务记录" }
        val e = state.entries[i]
        require(e.date <= now) { "不能提前完成未来任务" }
        require(status != "failed" || e.date < now) { "未到截止时间，请次日确认失败" }
        require(status != "pending" || e.date >= now || (e.kind ?: state.tasks.first { it.id == e.taskId }.kind) != "daily") { "往日每日任务请补记完成、豁免或记录未完成" }
        if(e.status == status) return
        transition(i, status, now, state.ledger.filter { it.source == id }.sumOf { it.amount })
        syncGoals(now)
    }
    private fun transition(index: Int, status: String, now: String, existing: Long,
        effectIndex: Int = state.effects.indexOfFirst { it.source == "task:${state.entries[index].id}" }) {
        val e = state.entries[index]
        val desired = when(status) { "done" -> e.points; "failed" -> -e.loss; else -> 0 }
        if(desired.toLong() != existing) state.ledger.add(Ledger(source = e.id, title = "${e.date} · ${e.title} · ${statusLabel(status)}", amount = desired.toLong() - existing, date = now))
        state.entries[index] = e.copy(status = status)
        effect("task:${e.id}", if(status == "failed") e.penalty else "", "penalty", now, effectIndex)
    }
    fun progress(g: Goal): Int {
        val limit = listOfNotNull(g.deadline, g.archivedOn).minOrNull()
        val items = state.entries.filter { it.taskId == g.taskId && it.date >= g.start && (limit == null || it.date <= limit) }.sortedBy { it.date }
        if(g.type == "manual") return if(g.outcome == "success") 1 else 0
        if(g.type == "count") return items.count { it.status == "done" }
        var run = 0; var best = 0
        items.forEach { when(it.status) { "done" -> { run++; best = maxOf(best, run) }; "waived" -> Unit; else -> run = 0 } }
        return best
    }
    // Legacy goals are immutable from v3 onward, including during backup migration.
    fun syncGoals(now: String = today()) = Unit
    fun saveCountdown(record: Countdown) {
        Countdowns.validate(record)
        val index = state.countdowns.indexOfFirst { it.id == record.id }
        if(index < 0) { require(state.countdowns.size < 100) { "最多保存 100 项倒计时" }; state.countdowns.add(record) }
        else state.countdowns[index] = record
    }
    fun deleteCountdown(id: String) { state.countdowns.removeAll { it.id == id } }
    private fun effect(source: String, text: String, kind: String, now: String, i: Int = state.effects.indexOfFirst { it.source == source }) {
        if(i < 0) { if(text.isNotBlank()) state.effects.add(Effect(source = source, title = text, kind = kind, date = now)); return }
        val old = state.effects[i]
        state.effects[i] = if(text.isBlank()) {
            if(old.status == "done") old.copy(corrected = true) else old.copy(status = "cancelled")
        } else {
            if(old.status == "done") old.copy(corrected = old.title != text) else old.copy(title = text, status = "pending", corrected = false)
        }
    }
    fun saveReward(r: Reward) {
        val i = state.rewards.indexOfFirst { it.id == r.id }
        if(i < 0) state.rewards.add(r) else state.rewards[i] = r
    }
    fun redeem(id: String, requestId: String, now: String = today()) {
        val source = "redeem:$requestId"
        if(state.ledger.any { it.source == source }) return
        val r = state.rewards.first { it.id == id && !it.archived }
        require(state.balance >= r.cost) { "积分不足，再完成一些任务吧" }
        state.ledger.add(Ledger(source = source, title = "兑换 · ${r.title}", amount = -r.cost.toLong(), date = now))
        effect(source, r.title, "reward", now)
    }
    fun cancelRedemption(id: String, now: String = today()) {
        val i = state.effects.indexOfFirst { it.id == id }; require(i >= 0)
        val e = state.effects[i]
        require(e.source.startsWith("redeem:") && e.status == "pending") { "仅未享用的兑换奖励可以退回" }
        val paid = state.ledger.filter { it.source == e.source }.sumOf { it.amount }
        state.ledger.add(Ledger(source = e.source, title = "退回 · ${e.title}", amount = -paid, date = now))
        state.effects[i] = e.copy(status = "cancelled")
    }
    fun finishEffect(id: String) {
        val i = state.effects.indexOfFirst { it.id == id }; require(i >= 0)
        require(state.effects[i].status == "pending")
        state.effects[i] = state.effects[i].copy(status = "done")
    }
}

fun statusLabel(s: String) = when(s) { "done" -> "已完成"; "waived" -> "已豁免"; "failed" -> "未完成"; else -> "待完成" }

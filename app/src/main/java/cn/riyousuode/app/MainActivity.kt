package cn.riyousuode.app

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.YearMonth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.io.ByteArrayOutputStream

class DayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DayApplication
    val state = app.store.state
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun run(after: () -> Unit = {}, block: Engine.() -> Unit) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { app.store.change(block); runCatching { state.value?.let { Reminders.schedule(app, it) } }.onFailure { error.value = "记录已保存，但系统未能安排提醒。请检查通知设置。" }; after() }
            catch(e: Exception) { error.value = e.message ?: "操作失败，请重试" }
            finally { busy.value = false }
        }
    }
    fun saveCountdown(record: Countdown, image: ByteArray?, after: () -> Unit) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { app.store.saveCountdown(record, image); after() }
            catch(e: Exception) { error.value = "保存失败：${e.message}" }
            finally { busy.value = false }
        }
    }
    fun refresh() = run { materialize(); syncGoals() }
    suspend fun export() = app.store.exportBundle()
    fun restore(bundle: BackupBundle, after: () -> Unit) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                app.store.restoreBundle(bundle)
                state.value?.let { Reminders.schedule(app, it) }
                after()
            } catch(e: Exception) { error.value = "恢复失败：${e.message}" }
            finally { busy.value = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: DayViewModel by viewModels()
    private var restoreText by mutableStateOf<BackupBundle?>(null)
    private var info by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refresh(); info = if(granted) "提醒已开启。请在任务中设置提醒时间。" else "未开启通知，仍可正常记录任务。"
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) vm.viewModelScope.launch {
            try {
                val data = vm.export()
                withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(data) } }
                info = "备份已导出，请妥善保存。"
            } catch(e: Exception) { vm.error.value = "导出失败：${e.message}" }
        }
    }
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) vm.viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        ArchiveBackup.decode(Pictures.readBounded(input, ArchiveBackup.LIMIT))
                    }
                }
                restoreText = text
            } catch(e: Exception) { vm.error.value = "备份文件无效：${e.message}" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DayTheme {
                App(vm,
                    onExport = { export.launch("日有所得-${today()}.zip") },
                    onImport = { import.launch(arrayOf("application/zip", "application/json", "text/plain", "application/octet-stream")) },
                    onPermission = {
                        if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else { vm.refresh(); info = "请在系统应用设置中允许通知，并在任务中设置时间。" }
                    },
                    onRecover = {
                        vm.viewModelScope.launch {
                            try { restoreText = withContext(Dispatchers.IO) { (filesDir.resolve("before-restore.zip").takeIf { it.exists() } ?: filesDir.resolve("before-restore.json")).inputStream().use { ArchiveBackup.decode(Pictures.readBounded(it, ArchiveBackup.LIMIT)) } } }
                            catch(e: Exception) { vm.error.value = "暂无恢复前快照，或快照无法读取。" }
                        }
                    }
                )
                restoreText?.let { text ->
                    val restored = text.state
                    Confirm("恢复备份", "将用 ${restored.tasks.size} 项任务、${restored.goals.size} 个旧目标、${restored.balance} 积分、${restored.moneyRecords.size} 笔收支和 ${restored.countdowns.size} 项倒计时覆盖当前所有记录及背景。旧版备份没有倒计时，v1 也没有记账。覆盖前自动保留含图片的完整快照。", { restoreText = null }) {
                        vm.restore(text) { restoreText = null; info = "备份已恢复。" }
                    }
                }
                info?.let { message -> AlertDialog(onDismissRequest = { info = null }, title = { Text("提示") }, text = { Text(message, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { info = null }) { Text("知道了") } }) }
            }
        }
    }
    override fun onResume() { super.onResume(); vm.refresh() }
}

private val Forest = Color(0xFF37664B)
private val Paper = Color(0xFFF7F8F2)
private val Ink = Color(0xFF24362C)
private val Muted = Color(0xFF69766C)
private val Clay = Color(0xFFA7543A)

@Composable fun DayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, primaryContainer = Color(0xFFE5EDD9), onPrimaryContainer = Ink, background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink, secondary = Forest, secondaryContainer = Color(0xFFE5EDD9), onSecondaryContainer = Ink, surfaceVariant = Color(0xFFE5E8DF), onSurfaceVariant = Muted, outline = Color(0xFF829084), outlineVariant = Color(0xFFDCE2D5), surfaceTint = Forest, error = Clay), content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun App(vm: DayViewModel, onExport: () -> Unit, onImport: () -> Unit, onPermission: () -> Unit, onRecover: () -> Unit) {
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var taskForm by remember { mutableStateOf(false) }; var editTask by remember { mutableStateOf<Task?>(null) }
    var countdownForm by remember { mutableStateOf(false) }; var editCountdown by remember { mutableStateOf<Countdown?>(null) }
    var legacyHistory by remember { mutableStateOf(false) }
    val calendarDay = rememberCalendarDay()
    var rewardForm by remember { mutableStateOf(false) }; var editReward by remember { mutableStateOf<Reward?>(null) }
    var moneyForm by remember { mutableStateOf(false) }; var editMoney by remember { mutableStateOf<MoneyRecord?>(null) }
    var moneyMonth by remember { mutableStateOf(YearMonth.now()) }; var moneyFilter by remember { mutableStateOf("all") }
    var deleteTaskId by remember { mutableStateOf<String?>(null) }
    var selectedEntry by remember { mutableStateOf<Entry?>(null) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var taskManager by remember { mutableStateOf(false) }
    val s = state
    LaunchedEffect(calendarDay) { vm.refresh() }
    LaunchedEffect(Unit) { while(true) { delay(60000); if(!vm.busy.value) vm.refresh() } }
    val tabs = listOf("今日", "倒计时", "奖惩", "记账", "我的")
    val icons = listOf(Icons.Outlined.CheckCircle, Icons.Outlined.Event, Icons.Outlined.CardGiftcard, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.PersonOutline)
    Scaffold(containerColor = Paper, bottomBar = {
        NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, name -> NavigationBarItem(selected = tab == i, onClick = { tab = i; history = false; taskManager = false; legacyHistory = false }, icon = { Icon(icons[i], name) }, label = { Text(name, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true)) }) }
        }
    }) { padding ->
        if(s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("正在读取本机记录", Modifier.padding(16.dp)); TextButton(onClick = { vm.refresh() }) { Text("重新读取") } } }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("日有所得  /  ${LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日"))}", fontSize = 12.sp, color = Muted, letterSpacing = 1.sp); Text(if(legacyHistory) "旧目标历史" else if(history) "我的足迹" else if(taskManager) "任务管理" else listOf("把今天过好", "让期待，有日期", "努力，值得回报", "把生活记清楚", "属于你的积累")[tab], fontSize = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    if(tab < 4 && !history && !taskManager && !legacyHistory) FilledTonalIconButton(onClick = { when(tab) { 0 -> { editTask = null; taskForm = true }; 1 -> { editCountdown = null; countdownForm = true }; 2 -> { editReward = null; rewardForm = true }; 3 -> { editMoney = null; moneyForm = true } } }) { Icon(Icons.Outlined.Add, "新增${listOf("任务", "倒计时", "奖励", "收支")[tab]}") }
                }
            }
            if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(legacyHistory) {
                item { TextButton(onClick = { legacyHistory = false }) { Text("返回我的") }; Text("旧目标已停用，不再结算。已有奖惩可在奖惩页兑现。", color = Muted) }
                if(s.goals.isEmpty()) item { Empty("没有旧目标", "新期待可以添加到倒计时。") }
                items(s.goals, key = { it.id }) { g -> Panel {
                    Text(g.title, fontSize=18.sp, fontWeight=FontWeight.Bold)
                    Text("历史状态：${when(g.outcome) { "success" -> "已达成"; "failed" -> "未达成"; else -> "未结算" }}${if(g.archived) " · 已归档" else ""}")
                    Text("${g.start} 开始 · ${g.deadline ?: "未设置截止日期"}", color=Muted)
                    if(g.reward.isNotBlank()) Text("原奖励：${g.reward}")
                    if(g.penalty.isNotBlank()) Text("原惩罚：${g.penalty}")
                } }
            } else if(history) {
                item { TextButton(onClick = { history = false }) { Text("返回我的") }; Text("点击任务可补记、更正或撤销；更正会同步调整积分，旧目标历史不再结算。", color = Muted, fontSize = 13.sp) }
                if(s.entries.isEmpty()) item { Empty("还没有足迹", "从今天的第一项任务开始。") }
                items(s.entries.sortedByDescending { it.date }, key = { it.id }) { e -> EntryCard(e, { selectedEntry = e }, { selectedEntry = e }) }
            } else if(taskManager) {
                item { TextButton(onClick = { taskManager = false }) { Text("返回今日") }; Text("修改仅影响之后生成的任务记录；已有记录保留原积分和奖惩。", fontSize = 13.sp, color = Muted) }
                val todayTaskIds = s.entries.filter { it.date == calendarDay.toString() }.map { it.taskId }.toSet()
                val todayTasks = s.tasks.filter { it.id in todayTaskIds }
                if(todayTasks.isEmpty()) item { Empty("今天没有任务", "点击今日右上角 + 添加任务；过往记录可在「我的 → 任务历史」查看。") }
                items(todayTasks, key = { it.id }) { t -> Panel {
                    Text(t.title, fontWeight = FontWeight.Bold); Text("${scheduleLabel(t)} · +${t.points} 积分${if(t.archived != null) " · 已归档" else ""}", color = Muted, fontSize = 13.sp)
                    FlowRow { if(t.archived == null) { TextButton(onClick = { editTask = t; taskForm = true }) { Text("编辑") }; TextButton(onClick = { confirm = Triple("归档任务", "停止生成未来任务，已有记录仍可结算和更正。") { vm.run { archiveTask(t.id) }; confirm = null } }) { Text("归档") } }; TextButton(enabled = !busy, onClick = { deleteTaskId = t.id }) { Text("删除", color = Clay) } }
                } }
            } else when(tab) {
                0 -> {
                    val dayEntries = s.entries.filter { it.date == today() }
                    val done = dayEntries.count { it.status == "done" }
                    val past = s.entries.filter { it.date < calendarDay.toString() && it.status == "pending" && it.kind != "daily" }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(24.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp)) {
                        Text("今日进度", color = Color(0xFFDFE8DB), fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.Bottom) { Text("$done", fontSize = 46.sp, color = Color.White, fontWeight = FontWeight.Bold); Text(" / ${dayEntries.size} 项", fontSize = 17.sp, color = Color(0xFFDFE8DB), modifier = Modifier.padding(bottom = 9.dp)) }
                            Text("${s.balance} 积分", color = Color.White, modifier = Modifier.padding(bottom = 9.dp))
                        }
                        LinearProgressIndicator(progress = { if(dayEntries.isEmpty()) 0f else done.toFloat() / dayEntries.size }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Color(0xFFCFDFA8), trackColor = Color(0xFF587B61))
                        Text(if(dayEntries.isNotEmpty() && done == dayEntries.size) "今天的努力，已经留下痕迹。" else "做好一件小事，也是在向前。", color = Color(0xFFDFE8DB), fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                    } } }
                    if(past.isNotEmpty()) {
                        item { Section("待你确认", "${past.size} 项往日任务") }
                        items(past.sortedBy { it.date }, key = { it.id }) { e -> EntryCard(e, { selectedEntry = e }, { selectedEntry = e }) }
                    }
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("今天的任务", fontSize = 19.sp, fontWeight = FontWeight.Bold); TextButton(onClick = { taskManager = true }) { Text("管理") } } }
                    if(dayEntries.isEmpty()) item { Empty("今天，留一点期待", "添加一个小任务，完成后给自己一点奖励。", "添加任务") { editTask = null; taskForm = true } }
                    items(dayEntries.sortedBy { it.status != "pending" }, key = { it.id }) { e -> EntryCard(e, { if(e.status == "pending") vm.run { settle(e.id, "done") } else selectedEntry = e }, { selectedEntry = e }) }
                }
                1 -> countdownPage(s, calendarDay, { editCountdown=null; countdownForm=true }, { editCountdown=it; countdownForm=true }, { c ->
                    confirm = Triple("删除倒计时", "确认删除「${c.title}」及其背景？此操作不可撤销。") { vm.run { deleteCountdown(c.id) }; confirm=null }
                })
                2 -> {
                    item { Panel { Text("可用积分", color = Muted); Text("${s.balance}", fontSize = 38.sp, fontWeight = FontWeight.Bold); Text("把期待写进奖励，给努力一个回响。", fontSize = 13.sp, color = Muted) } }
                    item { Section("奖励商店", "由你定义值得") }
                    if(s.rewards.none { !it.archived }) item { Empty("攒下一个小愿望", "一本书、一场电影，或者一段休息时间。", "添加奖励") { editReward = null; rewardForm = true } }
                    items(s.rewards.filter { !it.archived }, key = { it.id }) { r -> Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("${r.cost} 积分", color = Forest) }; Button(enabled = s.balance >= r.cost && !busy, onClick = { val request = uid(); confirm = Triple("兑换奖励", "花费 ${r.cost} 积分兑换「${r.title}」？未享用前可以退回。") { vm.run { redeem(r.id, request) }; confirm = null } }) { Text("兑换") } }
                        Row { TextButton(onClick = { editReward = r; rewardForm = true }) { Text("编辑") }; TextButton(onClick = { confirm = Triple("下架奖励", "停止新兑换，已有兑换记录会保留。") { vm.run { saveReward(r.copy(archived = true)) }; confirm = null } }) { Text("下架") } }
                    } }
                    item { Section("奖惩记录", "兑现你的约定") }
                    if(s.effects.isEmpty()) item { Text("奖励和惩罚会在这里留下记录。", color = Muted, fontSize = 14.sp) }
                    items(s.effects.asReversed(), key = { it.id }) { e -> Panel {
                        Text("${if(e.kind == "reward") "奖励" else "惩罚"} · ${when(e.status) { "done" -> "已兑现"; "cancelled" -> "已撤回"; else -> "待兑现" }} · ${e.date}", color = if(e.kind == "penalty") Clay else Forest, fontSize = 12.sp)
                        Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if(e.sourceDeleted) Text(if(e.status == "done") "来源已删除。这项奖惩已经兑现，请自行处理现实影响。" else "来源已删除，关联奖惩已撤回。", color = Clay, fontSize = 13.sp)
                        else if(e.corrected) Text("来源记录已更正。这项奖惩已经执行，请自行处理现实影响。", color = Clay, fontSize = 13.sp)
                        if(e.status == "pending") FlowRow {
                            TextButton(onClick = { confirm = Triple("确认已兑现", "确认你已经${if(e.kind == "reward") "享用这项奖励" else "执行这项惩罚"}？") { vm.run { finishEffect(e.id) }; confirm = null } }) { Text(if(e.kind == "reward") "已享用" else "已执行") }
                            if(e.source.startsWith("redeem:")) TextButton(onClick = { confirm = Triple("退回奖励", "取消这次兑换并退还积分。") { vm.run { cancelRedemption(e.id) }; confirm = null } }) { Text("退回积分") }
                        }
                    } }
                }
                3 -> moneyPage(s, moneyMonth, moneyFilter, { moneyMonth = it }, { moneyFilter = it }, { editMoney = null; moneyForm = true }, { editMoney = it; moneyForm = true }, { record ->
                    confirm = Triple("删除收支记录", "确认删除 ${record.date} 的${if(record.type == "income") "收入" else "支出"} ¥${Money.format(record.amountCents)}？此操作不可撤销。") { vm.run { deleteMoney(record.id) }; confirm = null }
                })
                4 -> {
                    item { Panel { Text("${s.entries.count { it.status == "done" }} 次完成", fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("${s.goals.count { it.outcome == "success" }} 个目标已达成 · ${s.balance} 可用积分", color = Muted); Text("数据只保存在这台手机，无需账号。", color = Muted, fontSize = 13.sp) } }
                    item { Panel {
                        Setting("旧目标历史", "只读查看，已有奖惩继续保留", { legacyHistory = true })
                        HorizontalDivider()
                        Setting("任务历史", "补记、豁免和更正过往任务", { history = true })
                        HorizontalDivider()
                        Setting("开启任务提醒", "使用系统通知，省电策略可能使提醒延迟", onPermission)
                        HorizontalDivider()
                        Setting("导出备份", "换手机或卸载前，请先保存含背景图片的 ZIP 备份", onExport)
                        Setting("恢复备份", "校验后覆盖当前记录", onImport)
                        Setting("恢复上次覆盖前的数据", "本机保留最近一次恢复前快照", onRecover)
                    } }
                    item { Section("积分流水", "每一笔都有来处") }
                    if(s.ledger.isEmpty()) item { Text("完成任务后，你的第一笔积分会出现在这里。", color = Muted) }
                    items(s.ledger.asReversed(), key = { it.id }) { l -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(l.title, fontSize = 14.sp); Text(l.date, fontSize = 12.sp, color = Muted) }
                        Text(if(l.amount >= 0) "+${l.amount}" else "${l.amount}", fontWeight = FontWeight.Bold, color = if(l.amount < 0) Clay else Forest)
                    } }
                    item { Text("日有所得 0.3.2 · 本地个人版\n设计参考 HabitTrackerApp（MIT）。\n通知、记录与奖惩均由你自行管理。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 18.dp)) }
                }
            }
        }
    }
    if(moneyForm) MoneyForm(editMoney, busy, { moneyForm = false }) { record -> vm.run(after = { moneyForm = false; moneyMonth = YearMonth.from(LocalDate.parse(record.date)) }) { saveMoney(record) } }
    deleteTaskId?.let { id -> if(s != null) {
        val task = s.tasks.find { it.id == id }
        if(task != null) AlertDialog(onDismissRequest = { if(!busy) deleteTaskId = null }, title = { Text("删除「${task.title}」") }, text = { Text(Engine(s).deletionImpact(id).description(), Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.run(after = { deleteTaskId = null }) { deleteTask(id) } }) { Text("确认删除", color = Clay) } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleteTaskId = null }) { Text("取消") } })
    } }
    if(taskForm) TaskForm(editTask, busy, { taskForm = false }) { t -> vm.run(after = { taskForm = false }) { saveTask(t) } }
    if(countdownForm) CountdownForm(editCountdown, busy, { countdownForm=false }) { record, image -> vm.saveCountdown(record,image) { countdownForm=false } }
    if(rewardForm) RewardForm(editReward, busy, { rewardForm = false }) { r -> vm.run(after = { rewardForm = false }) { saveReward(r) } }
    selectedEntry?.let { e -> AlertDialog(onDismissRequest = { selectedEntry = null }, title = { Text(e.title) }, text = { Column {
        Text("${e.date} · ${statusLabel(e.status)}\n完成 +${e.points}；失败 -${e.loss} 积分。${if(e.penalty.isBlank()) "" else "\n失败还需：${e.penalty}"}")
        Text("补记归入原日期；豁免不计分也不中断连续记录。已兑现奖惩的现实影响需要自行处理。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
        listOf("done" to "${if(e.date < today()) "补记" else "标记"}完成", "waived" to "豁免这一次", "failed" to "确认未完成", "pending" to "撤销 / 恢复待完成").filter { it.first != e.status && (it.first != "failed" || e.date < today()) && (it.first != "pending" || e.date >= today() || e.kind != "daily") }.forEach { (status, label) ->
            TextButton(enabled = !busy, onClick = { vm.run(after = { selectedEntry = null }) { settle(e.id, status) } }) { Text(label) }
        }
    } }, confirmButton = { TextButton(onClick = { selectedEntry = null }) { Text("关闭") } }) }
    confirm?.let { (title, message, action) -> Confirm(title, message, { confirm = null }, action) }
    error?.let { message -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("未能完成操作") }, text = { Text(message, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("知道了") } }) }
}

@Composable fun Panel(content: @Composable ColumnScope.() -> Unit) { Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFE5E8DF))) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content) } }
@Composable fun Section(title: String, subtitle: String) { Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 12.sp) } }
@Composable fun Empty(title: String, detail: String, button: String? = null, action: () -> Unit = {}) { Panel { Icon(Icons.Outlined.Spa, null, tint = Forest, modifier = Modifier.size(32.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(detail, color = Muted, fontSize = 14.sp); if(button != null) FilledTonalButton(onClick = action) { Text(button) } } }
@Composable fun Setting(title: String, subtitle: String, action: () -> Unit) { Column(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 10.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 12.sp, color = Muted) } }
@Composable fun EntryCard(e: Entry, action: () -> Unit, details: () -> Unit) { Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE5E8DF))) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = action) { Icon(if(e.status == "done") Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if(e.status == "pending") "完成 ${e.title}" else "更正 ${e.title}", tint = if(e.status == "done") Forest else Muted, modifier = Modifier.size(29.dp)) }
    Column(Modifier.weight(1f).clickable(onClick = details).padding(vertical = 6.dp)) { Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.Medium); Text("${if(e.date != today()) "${e.date} · " else ""}${statusLabel(e.status)} · +${e.points} 积分", fontSize = 12.sp, color = Muted) }
    IconButton(onClick = details) { Icon(Icons.Outlined.MoreHoriz, "任务选项") }
} } }
@Composable fun Confirm(title: String, message: String, dismiss: () -> Unit, action: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = action) { Text("确认") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }) }
fun scheduleLabel(t: Task) = when(t.kind) { "once" -> "${t.start} 一次"; "weekly" -> "每周 ${t.weekdays.joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] }}"; else -> "每天" }

@Composable fun Form(title: String, busy: Boolean, dismiss: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = { if(!busy) dismiss() }, title = { Text(title) }, text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }, confirmButton = { TextButton(enabled = !busy, onClick = save) { Text(if(busy) "保存中…" else "保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("取消") } })
}
@Composable fun Field(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, enabled: Boolean = true) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = if(numeric) KeyboardType.Number else KeyboardType.Text), shape = RoundedCornerShape(12.dp)) }
@OptIn(ExperimentalLayoutApi::class)
@Composable fun Choices(options: List<Pair<String, String>>, value: String, change: (String) -> Unit) { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { (key, title) -> FilterChip(selected = value == key, onClick = { change(key) }, label = { Text(title) }) } } }

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TaskForm(old: Task?, busy: Boolean, dismiss: () -> Unit, save: (Task) -> Unit) {
    var title by remember { mutableStateOf(old?.title ?: "") }; var note by remember { mutableStateOf(old?.note ?: "") }
    var kind by remember { mutableStateOf(old?.kind ?: "daily") }; var start by remember { mutableStateOf(old?.start ?: today()) }
    var days by remember { mutableStateOf(old?.weekdays ?: (1..7).toList()) }
    var points by remember { mutableStateOf((old?.points ?: 10).toString()) }; var loss by remember { mutableStateOf((old?.loss ?: 0).toString()) }
    var penalty by remember { mutableStateOf(old?.penalty ?: "") }; var reminder by remember { mutableStateOf(old?.reminder ?: "") }; var error by remember { mutableStateOf("") }
    Form(if(old == null) "新建任务" else "编辑任务", busy, dismiss, {
        try {
            require(title.isNotBlank()) { "请填写任务名称" }
            require(old != null || start >= today()) { "新任务不能从过去开始" }
            val task = Task(old?.id ?: uid(), title.trim(), note.trim(), start, kind, days.sorted(), points.toInt(), loss.toInt(), penalty.trim(), reminder.trim(), old?.archived)
            Backup.validate(AppState(tasks = mutableListOf(task))); save(task)
        } catch(e: Exception) { error = e.message ?: "请检查输入" }
    }) {
        Field("任务名称", title, { title = it }); Field("备注（可选）", note, { note = it })
        Choices(listOf("daily" to "每天", "weekly" to "每周", "once" to "一次性"), kind) { kind = it }
        if(kind == "weekly") FlowRow { (1..7).forEach { d -> FilterChip(selected = d in days, onClick = { days = if(d in days) days - d else days + d }, label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[d - 1]) }) } }
        Field("开始日期 YYYY-MM-DD", start, { start = it }, enabled = old == null)
        Field("完成获得积分", points, { points = it }, true); Field("失败扣除积分", loss, { loss = it }, true)
        Field("失败还需做什么（可选）", penalty, { penalty = it }); Field("提醒时间 HH:mm（可选）", reminder, { reminder = it })
        Text(if(old == null) when(kind) { "daily" -> "每日任务过当天未完成会自动记为未完成，并按设置扣分或生成惩罚；补记请到任务历史。"; "once" -> "一次性任务未完成会继续保留到第二天，等待你完成或确认。"; else -> "每周任务未完成仍等待你确认，不自动扣分。" } else "修改只影响之后生成的记录，今天和历史任务保留原规则。", fontSize = 12.sp, color = Muted)
        if(error.isNotEmpty()) Text(error, color = Clay)
    }
}
@Composable fun RewardForm(old: Reward?, busy: Boolean, dismiss: () -> Unit, save: (Reward) -> Unit) {
    var title by remember { mutableStateOf(old?.title ?: "") }; var cost by remember { mutableStateOf((old?.cost ?: 50).toString()) }; var error by remember { mutableStateOf("") }
    Form(if(old == null) "添加奖励" else "编辑奖励", busy, dismiss, {
        try { val r = Reward(old?.id ?: uid(), title.trim(), cost.toInt()); Backup.validate(AppState(rewards = mutableListOf(r))); save(r) } catch(e: Exception) { error = e.message ?: "请检查输入" }
    }) { Field("想给自己的奖励", title, { title = it }); Field("兑换所需积分", cost, { cost = it }, true); Text("兑换扣除积分，享用之后再确认兑现。", fontSize = 13.sp, color = Muted); if(error.isNotEmpty()) Text(error, color = Clay) }
}

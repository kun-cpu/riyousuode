package cn.riyousuode.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.util.Locale

private val MoneyGreen = Color(0xFF37664B)
private val MoneyMuted = Color(0xFF69766C)
private val MoneyClay = Color(0xFFA7543A)

fun LazyListScope.moneyPage(
    state: AppState, month: YearMonth, filter: String, changeMonth: (YearMonth) -> Unit,
    changeFilter: (String) -> Unit, add: () -> Unit, edit: (MoneyRecord) -> Unit, delete: (MoneyRecord) -> Unit
) {
    val summary = Money.summary(state.moneyRecords, month)
    val records = Money.records(state.moneyRecords, month, filter)
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(enabled = month > YearMonth.of(2000, 1), onClick = { changeMonth(month.minusMonths(1)) }) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            Text("${month.year} 年 ${month.monthValue} 月", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            IconButton(enabled = month < YearMonth.of(2100, 12), onClick = { changeMonth(month.plusMonths(1)) }) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        if(month != YearMonth.now()) TextButton(onClick = { changeMonth(YearMonth.now()) }) { Text("回到本月") }
    }
    item {
        Card(colors = CardDefaults.cardColors(containerColor = MoneyGreen), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("本月结余 · 元", color = Color(0xFFDFE8DB), fontSize = 13.sp)
                Text("¥${Money.format(summary.net)}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = Color(0xFF6A8B72))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text("收入", color = Color(0xFFDFE8DB), fontSize = 13.sp); Text("¥${Money.format(summary.income)}", color = Color.White, fontSize = 18.sp) }
                    Column(Modifier.weight(1f)) { Text("支出", color = Color(0xFFDFE8DB), fontSize = 13.sp); Text("¥${Money.format(summary.expense)}", color = Color.White, fontSize = 18.sp) }
                }
                Text("当月收入减支出，与积分及账户余额无关。", color = Color(0xFFDFE8DB), fontSize = 12.sp)
            }
        }
    }
    item { Choices(listOf("all" to "全部", "expense" to "支出", "income" to "收入"), filter, changeFilter) }
    item { Section("收支明细", "${records.size} 笔") }
    if(records.isEmpty()) item { Empty("这个月，还没有${when(filter) { "income" -> "收入"; "expense" -> "支出"; else -> "收支" }}记录", "记下一笔，让生活的开销更清楚。", "记一笔", add) }
    items(records, key = { "money:${it.id}" }) { record ->
        Panel {
            Text("${record.date} · ${if(record.type == "income") "收入" else "支出"}", color = MoneyMuted, fontSize = 12.sp)
            Text(record.category, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${if(record.type == "income") "+" else "−"}¥${Money.format(record.amountCents)}", color = if(record.type == "income") MoneyGreen else MoneyClay, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if(record.note.isNotBlank()) Text(record.note, fontSize = 14.sp, color = MoneyMuted)
            Row { TextButton(onClick = { edit(record) }) { Text("编辑") }; TextButton(onClick = { delete(record) }) { Text("删除", color = MoneyClay) } }
        }
    }
    val types = if(filter == "all") listOf("expense", "income") else listOf(filter)
    types.forEach { type ->
        val categories = Money.breakdown(state.moneyRecords, month, type)
        if(categories.isNotEmpty()) {
            item { Section(if(type == "expense") "支出分类" else "收入分类", "当月占比") }
            items(categories, key = { "category:$type:${it.category}" }) { category ->
                Panel {
                    Text("${category.category} · ${String.format(Locale.ROOT, "%.1f", category.fraction * 100)}%", fontWeight = FontWeight.SemiBold)
                    Text("¥${Money.format(category.cents)}", color = MoneyMuted)
                    LinearProgressIndicator(progress = { category.fraction }, modifier = Modifier.fillMaxWidth(), color = if(type == "expense") MoneyClay else MoneyGreen)
                }
            }
        }
    }
}

@Composable fun MoneyForm(old: MoneyRecord?, busy: Boolean, dismiss: () -> Unit, save: (MoneyRecord) -> Unit) {
    val id = remember { old?.id ?: uid() }
    var type by remember { mutableStateOf(old?.type ?: "expense") }
    var amount by remember { mutableStateOf(old?.let { Money.format(it.amountCents) } ?: "") }
    var category by remember { mutableStateOf(old?.category ?: Money.expenseCategories.first()) }
    var date by remember { mutableStateOf(old?.date ?: today()) }
    var note by remember { mutableStateOf(old?.note ?: "") }
    var error by remember { mutableStateOf("") }
    Form(if(old == null) "记一笔" else "编辑收支", busy, dismiss, {
        try { val record = MoneyRecord(id, type, Money.parse(amount), category, date.trim(), note.trim()); Money.validate(record); save(record) }
        catch(_: java.time.DateTimeException) { error = "日期请填写为 YYYY-MM-DD，例如 ${today()}" }
        catch(e: Exception) { error = e.message ?: "请检查输入" }
    }) {
        Choices(listOf("expense" to "支出", "income" to "收入"), type) { if(it != type) { type = it; category = Money.categories(it).first() } }
        OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
        Text("分类", fontSize = 13.sp, color = MoneyMuted)
        Choices(Money.categories(type).map { it to it }, category) { category = it }
        Field("日期 YYYY-MM-DD", date, { date = it })
        Field("备注（可选）", note, { note = it })
        Text("仅记录人民币收支，不影响任务积分。", fontSize = 12.sp, color = MoneyMuted)
        if(error.isNotBlank()) Text(error, color = MoneyClay)
    }
}

package cn.riyousuode.app

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class DeletionImpact(val entries: Int, val goals: List<String>, val adjustment: Long, val pendingEffects: Int, val doneEffects: Int) {
    fun description() = "将永久删除 $entries 条打卡记录及 ${goals.size} 个关联目标。" +
        (if(goals.isEmpty()) "" else "\n关联目标：${goals.joinToString("、")}") +
        "\n积分调整：${if(adjustment >= 0) "+" else ""}$adjustment（余额允许变负）。" +
        "\n撤回 $pendingEffects 项未兑现奖惩，保留 $doneEffects 项已兑现凭证并标注来源已删除。" +
        "\n积分流水与商店兑换记录保留，提醒停止。此操作不可撤销。"
}

@Serializable data class MoneyRecord(
    val id: String = uid(), val type: String, val amountCents: Long,
    val category: String, val date: String = today(), val note: String = ""
)
data class CategoryTotal(val category: String, val cents: Long, val fraction: Float)
data class MonthSummary(val income: Long, val expense: Long) { val net get() = income - expense }

object Money {
    private const val MAX_CENTS = 9_999_999_999L
    val expenseCategories = listOf("餐饮", "交通", "购物", "居住", "娱乐", "学习", "医疗", "其他")
    val incomeCategories = listOf("工资", "奖金", "兼职", "红包", "其他收入")
    fun categories(type: String) = if(type == "income") incomeCategories else expenseCategories
    fun parse(text: String): Long {
        val value = text.trim()
        require(value.length <= 16 && Regex("[0-9]+(\\.[0-9]{1,2})?").matches(value)) { "金额须为正数，最多两位小数" }
        val cents = try { BigDecimal(value).movePointRight(2).longValueExact() } catch(_: ArithmeticException) { throw IllegalArgumentException("金额过大") }
        require(cents in 1..MAX_CENTS) { "金额须在 0.01–99,999,999.99 元之间" }
        return cents
    }
    fun format(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
    fun validate(r: MoneyRecord) {
        require(r.id.isNotBlank() && r.id.length <= 120)
        require(r.type in listOf("income", "expense")) { "请选择收入或支出" }
        require(r.amountCents in 1..MAX_CENTS) { "金额超出范围" }
        require(r.category in categories(r.type)) { "收支分类无效" }
        require(LocalDate.parse(r.date).year in 2000..2100) { "日期超出支持范围" }
        require(r.note.length <= 1000) { "备注不能超过 1000 字" }
    }
    fun records(records: List<MoneyRecord>, month: YearMonth, filter: String = "all") = records.filter {
        YearMonth.from(LocalDate.parse(it.date)) == month && (filter == "all" || it.type == filter)
    }.asReversed().sortedByDescending { it.date }
    fun summary(records: List<MoneyRecord>, month: YearMonth): MonthSummary {
        val monthRecords = records(records, month)
        return MonthSummary(monthRecords.filter { it.type == "income" }.sumOf { it.amountCents }, monthRecords.filter { it.type == "expense" }.sumOf { it.amountCents })
    }
    fun breakdown(records: List<MoneyRecord>, month: YearMonth, type: String): List<CategoryTotal> {
        val totals = records(records, month, type).groupBy { it.category }.mapValues { (_, items) -> items.sumOf { it.amountCents } }
        val total = totals.values.sum()
        return totals.map { CategoryTotal(it.key, it.value, if(total == 0L) 0f else it.value.toFloat() / total) }.sortedByDescending { it.cents }
    }
}

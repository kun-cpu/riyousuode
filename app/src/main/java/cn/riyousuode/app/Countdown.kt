package cn.riyousuode.app

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable data class Countdown(val id: String = uid(), val title: String, val date: String,
    val background: String = "lake", val photo: String? = null)

object Countdowns {
    val backgrounds = listOf("lake" to "湖泊", "mountain" to "山川", "sunset" to "日落")
    fun validate(c: Countdown) {
        require(c.id.isNotBlank() && c.id.length <= 120)
        require(c.title.isNotBlank() && c.title.length <= 100) { "名称须为 1–100 个字" }
        require(LocalDate.parse(c.date).year in 2000..2100) { "日期超出支持范围" }
        require(c.background in backgrounds.map { it.first })
        require(c.photo == null || Regex("[a-f0-9]{64}\\.jpg").matches(c.photo)) { "图片引用无效" }
    }
    fun days(c: Countdown, now: LocalDate = LocalDate.now()) = ChronoUnit.DAYS.between(now, LocalDate.parse(c.date))
    fun label(c: Countdown, now: LocalDate = LocalDate.now()): String = days(c, now).let {
        when { it > 0 -> "还有 $it 天"; it < 0 -> "已过去 ${-it} 天"; else -> "就是今天" }
    }
    fun sorted(items: List<Countdown>, now: LocalDate = LocalDate.now()) =
        items.filter { days(it, now) >= 0 }.sortedWith(compareBy<Countdown> { it.date }.thenBy { it.id }) +
        items.filter { days(it, now) < 0 }.sortedWith(compareByDescending<Countdown> { it.date }.thenBy { it.id })
}

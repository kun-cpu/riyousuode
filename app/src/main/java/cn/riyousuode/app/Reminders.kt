package cn.riyousuode.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.*

object Reminders {
    private const val CHANNEL = "daily_tasks"
    fun available(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun schedule(context: Context, state: AppState) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        prefs.getStringSet("scheduled", emptySet())!!.forEach { id ->
            manager.cancel(pending(context, id))
        }
        val scheduled = mutableSetOf<String>()
        if(available(context)) state.tasks.filter { it.archived == null && it.reminder.isNotBlank() }.forEach { t ->
            val now = ZonedDateTime.now()
            var date = maxOf(now.toLocalDate(), LocalDate.parse(t.start))
            var target: ZonedDateTime? = null
            repeat(9) {
                val due = t.kind == "daily" || (t.kind == "once" && date.toString() == t.start) || (t.kind == "weekly" && date.dayOfWeek.value in t.weekdays)
                val done = state.entries.any { e -> e.taskId == t.id && e.date == date.toString() && e.status != "pending" }
                val time = date.atTime(LocalTime.parse(t.reminder)).atZone(ZoneId.systemDefault())
                if(target == null && due && !done && time.isAfter(now)) target = time
                date = date.plusDays(1)
            }
            target?.let {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toInstant().toEpochMilli(), pending(context, t.id, it.toLocalDate().toString()))
                scheduled.add(t.id)
            }
        }
        prefs.edit().putStringSet("scheduled", scheduled).apply()
    }
    private fun pending(context: Context, id: String, date: String = ""): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setData(android.net.Uri.parse("riyousuode://task/$id")).putExtra("taskId", id).putExtra("date", date),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    fun notify(context: Context, task: Task) {
        if(!available(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(task.id.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(cn.riyousuode.app.R.drawable.ic_leaf).setContentTitle(task.title)
            .setContentText("留一点时间给自己，完成后记得打卡。")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as DayApplication
        app.scope.launch {
            try {
                app.store.refresh()
                val state = app.store.state.value ?: return@launch
                val t = state.tasks.find { it.id == intent.getStringExtra("taskId") && it.archived == null }
                if(t != null && intent.getStringExtra("date") == today() && state.entries.any { it.taskId == t.id && it.date == today() && it.status == "pending" }) Reminders.notify(context, t)
                Reminders.schedule(context, state)
            } catch(e: Exception) { android.util.Log.e("DayReminder", "Unable to refresh reminder", e) }
            finally { pending.finish() }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val app = context.applicationContext as DayApplication
        app.scope.launch { try { app.store.refresh(); app.store.state.value?.let { Reminders.schedule(context, it) } } catch(e: Exception) { android.util.Log.e("DayReminder", "Unable to reschedule", e) } finally { pending.finish() } }
    }
}

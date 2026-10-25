package com.gemmahack.symptomdiary

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.ZonedDateTime

object LocalReminders {
    fun schedule(context: Context, visitId: String, reminder: ActionReminder) {
        val time = ZonedDateTime.parse(reminder.at).toInstant().toEpochMilli()
        require(time > System.currentTimeMillis()) { "Choose a future time." }
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time,
            pending(context, visitId, reminder.id))
    }
    fun cancel(context: Context, visitId: String, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, visitId, id))
    }
    private fun pending(context: Context, visitId: String, id: String): PendingIntent = PendingIntent.getBroadcast(context,
        id.hashCode(), Intent(context, ReminderReceiver::class.java).setAction("reminder:$id").putExtra("visit", visitId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("visit") ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("actions", "Saved action reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java)
            .putExtra("visit", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id.hashCode(), NotificationCompat.Builder(context, "actions").setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("Advo").setContentText("Review a saved next step.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build())
    }
}

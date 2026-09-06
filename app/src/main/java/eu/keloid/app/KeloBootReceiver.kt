package eu.keloid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class KeloBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("kelo_notifications", Context.MODE_PRIVATE)
        if (prefs.getString("subject_did", null).isNullOrBlank()) return

        val request = PeriodicWorkRequestBuilder<KeloNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(KeloNotificationWorker.constraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            KeloNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

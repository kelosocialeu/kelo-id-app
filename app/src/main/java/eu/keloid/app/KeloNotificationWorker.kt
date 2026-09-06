package eu.keloid.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class KeloNotificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success()
        }

        runCatching {
            val prefs = applicationContext.getSharedPreferences("kelo_notifications", Context.MODE_PRIVATE)
            val did = prefs.getString("subject_did", null)
            val url = buildString {
                append("https://kelo-id.eu/api/notifications")
                if (!did.isNullOrBlank()) append("?did=").append(Uri.encode(did))
            }
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val json = response.body?.string().orEmpty()
                val notifications = JSONArray(org.json.JSONObject(json).optJSONArray("notifications")?.toString() ?: "[]")
                if (notifications.length() == 0) return@use
                val newest = notifications.getJSONObject(0)
                val id = newest.optString("id")
                if (id.isBlank() || id == prefs.getString("last_id", null)) return@use

                val title = newest.optString("title", "Kelo ID")
                val body = newest.optString("body", "Nouvelle notification Kelo ID")
                val targetUrl = newest.optString("url", "https://kelo-id.eu/")
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    data = targetUrl.takeIf { it.startsWith("https://kelo-id.eu") || it.startsWith("https://www.kelo-id.eu") }?.let(Uri::parse)
                }
                val pendingIntent = PendingIntent.getActivity(applicationContext, 2001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(applicationContext, "kelo_id_general")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title.take(80))
                    .setContentText(body.take(200))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
                prefs.edit().putString("last_id", id).apply()
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val WORK_NAME = "kelo-id-notifications"
        fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

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
import org.json.JSONObject

class KeloNotificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("kelo_notifications", Context.MODE_PRIVATE)
        val did = prefs.getString("subject_did", null)
        if (did.isNullOrBlank()) return@withContext Result.success()

        runCatching {
            val url = "https://kelo-id.eu/api/notifications?did=${Uri.encode(did)}"
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val json = response.body?.string().orEmpty()
                val notifications = JSONArray(JSONObject(json).optJSONArray("notifications")?.toString() ?: "[]")
                if (notifications.length() == 0) return@use

                val seen = prefs.getStringSet("seen_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
                val newItems = mutableListOf<JSONObject>()

                for (index in 0 until notifications.length()) {
                    val item = notifications.getJSONObject(index)
                    val id = item.optString("id")
                    if (id.isNotBlank() && !seen.contains(id)) newItems += item
                }

                if (seen.isEmpty()) {
                    val newest = notifications.getJSONObject(0)
                    val id = newest.optString("id")
                    if (id.isNotBlank()) {
                        showNotification(newest, id)
                        seen.add(id)
                    }
                    for (index in 1 until notifications.length()) {
                        val id = notifications.getJSONObject(index).optString("id")
                        if (id.isNotBlank()) seen.add(id)
                    }
                } else {
                    newItems.asReversed().forEach { item ->
                        val id = item.optString("id")
                        if (id.isNotBlank()) {
                            showNotification(item, id)
                            seen.add(id)
                        }
                    }
                }

                while (seen.size > 100) seen.remove(seen.first())
                prefs.edit().putStringSet("seen_ids", seen).remove("last_id").apply()
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun showNotification(item: JSONObject, id: String) {
        val title = item.optString("title", "Kelo ID")
        val body = item.optString("body", "Nouvelle notification Kelo ID")
        val targetUrl = item.optString("url", "https://kelo-id.eu/")
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            data = targetUrl.takeIf { it.startsWith("https://kelo-id.eu") || it.startsWith("https://www.kelo-id.eu") }?.let(Uri::parse)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "kelo_id_general")
            .setSmallIcon(R.drawable.ic_kelo_id_notification)
            .setContentTitle(title.take(80))
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }

    companion object {
        const val WORK_NAME = "kelo-id-notifications"
        fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

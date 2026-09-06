package eu.keloid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class KeloFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FCM_TOKEN, token).apply()
        registerToken(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Kelo ID"
        val body = message.notification?.body ?: message.data["body"] ?: "Vous avez une nouvelle notification."
        val url = message.data["url"]?.takeIf { it.startsWith("https://kelo-id.eu") || it.startsWith("https://www.kelo-id.eu") }
        showNotification(title, body, url)
    }

    private fun showNotification(title: String, body: String, url: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val target = Intent(this, MainActivity::class.java).apply {
            url?.let { data = Uri.parse(it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            title.hashCode(),
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Kelo ID",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Notifications importantes de Kelo ID" }
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kelo_id_notification)
            .setContentTitle(title.take(80))
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500)))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(title.hashCode(), notification)
    }

    companion object {
        private const val PREFS = "kelo_notifications"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val CHANNEL_ID = "kelo_id_general"
        private const val REGISTER_URL = "https://kelo-id.eu/api/push/register"

        fun registerToken(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_FCM_TOKEN, null) ?: return
            val did = prefs.getString("subject_did", null)?.takeIf { it.startsWith("did:") } ?: return

            Thread {
                runCatching {
                    val payload = JSONObject().apply {
                        put("did", did)
                        put("token", token)
                        put("platform", "android")
                    }.toString()
                    val request = Request.Builder()
                        .url(REGISTER_URL)
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) Log.w("KeloFCM", "Token registration failed: ${response.code}")
                    }
                }.onFailure { Log.w("KeloFCM", "Token registration error", it) }
            }.start()
        }
    }
}

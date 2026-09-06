package eu.keloid.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request == null) return@registerForActivityResult
        if (granted && hasPermission(Manifest.permission.CAMERA)) request.grant(request.resources) else request.deny()
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermission()
        scheduleNotificationWorker()
        setupWebView()
        setContentView(webView)
        handleIncomingIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setupWebView() {
        webView = WebView(this)
        webView.setBackgroundColor(0x00000000)
        webView.addJavascriptInterface(AndroidNotificationBridge(), "KeloAndroidNotifications")

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            userAgentString = "$userAgentString KeloIDAndroid/1.0"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return true
                return if (host == "kelo-id.eu" || host == "www.kelo-id.eu") false
                else { runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }; true }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val host = request.origin.host?.lowercase()
                    if (host != "kelo-id.eu" && host != "www.kelo-id.eu") { request.deny(); return@runOnUiThread }
                    val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!needsCamera) { request.deny(); return@runOnUiThread }
                    if (hasPermission(Manifest.permission.CAMERA)) request.grant(request.resources)
                    else {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return runCatching { fileChooserLauncher.launch(fileChooserParams.createIntent()); true }.getOrElse { pendingFileCallback = null; false }
            }
        }

        webView.loadUrl("https://kelo-id.eu/")
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val host = uri.host?.lowercase()
        if (intent.scheme != "keloid" && host != "kelo-id.eu" && host != "www.kelo-id.eu") return
        webView.post { webView.loadUrl(uri.toString()) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun scheduleNotificationWorker() {
        val request = PeriodicWorkRequestBuilder<KeloNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(KeloNotificationWorker.constraints())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(KeloNotificationWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("kelo_id_general", "Kelo ID", NotificationManager.IMPORTANCE_HIGH).apply { description = "Notifications importantes de Kelo ID" }
        )
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private inner class AndroidNotificationBridge {
        @JavascriptInterface
        fun setSubjectDid(did: String) {
            if (did.startsWith("did:")) getSharedPreferences("kelo_notifications", MODE_PRIVATE).edit().putString("subject_did", did).apply()
        }

        @JavascriptInterface
        fun notify(title: String, body: String, url: String?) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return@runOnUiThread
                val target = Intent(this@MainActivity, MainActivity::class.java).apply { data = url?.takeIf { it.startsWith("https://kelo-id.eu") || it.startsWith("https://www.kelo-id.eu") }?.let(Uri::parse) }
                val pendingIntent = PendingIntent.getActivity(this@MainActivity, 1001, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this@MainActivity, "kelo_id_general") else android.app.Notification.Builder(this@MainActivity)
                val notification = builder.setSmallIcon(R.drawable.ic_kelo_id_notification).setContentTitle(title.take(80)).setContentText(body.take(200)).setAutoCancel(true).setContentIntent(pendingIntent).build()
                getSystemService(NotificationManager::class.java).notify(title.hashCode(), notification)
            }
        }
    }

    override fun onDestroy() {
        pendingWebPermission?.deny(); pendingWebPermission = null
        pendingFileCallback?.onReceiveValue(null); pendingFileCallback = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("KeloAndroidNotifications")
            webView.stopLoading(); webView.webChromeClient = null; webView.destroy()
        }
        super.onDestroy()
    }
}

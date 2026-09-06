package eu.keloid.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import eu.keloid.nfc.IcaoMrtdReader
import eu.keloid.nfc.IdentityAccessCredentials
import eu.keloid.nfc.NfcProofBuilder
import eu.keloid.nfc.NfcSigningKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val nfcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var activeNfcOperationId: String? = null
    private var activeNfcSubjectDid: String? = null
    private var activeNfcCredentials: IdentityAccessCredentials? = null
    private var activeNfcKeyManager: NfcSigningKeyManager? = null

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
        configureSystemBars()
        createNotificationChannel()
        requestNotificationPermission()
        initializeFirebaseMessaging()
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

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun setupWebView() {
        webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(webView)

        webView.addJavascriptInterface(AndroidNotificationBridge(), "KeloAndroidNotifications")
        webView.addJavascriptInterface(KeloIdNfcBridge(), "KeloIdNfcNative")

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
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installNfcJavascriptBridge()
                pushFcmTokenToWebView()
            }

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

    private fun installNfcJavascriptBridge() {
        if (!::webView.isInitialized) return
        val script = """
            (function() {
              if (!window.KeloIdNfcNative) return;
              window.__keloNfcPending = window.__keloNfcPending || {};
              window.__keloNfcResolve = function(id, json, error) {
                var pending = window.__keloNfcPending[id];
                if (!pending) return;
                delete window.__keloNfcPending[id];
                if (error) pending.reject(new Error(error));
                else {
                  try { pending.resolve(JSON.parse(json)); }
                  catch (e) { pending.reject(e); }
                }
              };
              window.KeloIdNfc = {
                getAvailability: async function() {
                  return JSON.parse(window.KeloIdNfcNative.getAvailability());
                },
                getDeviceSigningKey: async function() {
                  return JSON.parse(window.KeloIdNfcNative.getDeviceSigningKey());
                },
                readIdentityCard: function(request) {
                  return new Promise(function(resolve, reject) {
                    try {
                      var id = window.KeloIdNfcNative.readIdentityCard(JSON.stringify(request));
                      window.__keloNfcPending[id] = { resolve: resolve, reject: reject };
                    } catch (e) { reject(e); }
                  });
                }
              };
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun completeNfcOperation(operationId: String, proof: JSONObject? = null, error: String? = null) {
        if (!::webView.isInitialized) return
        val id = JSONObject.quote(operationId)
        val json = JSONObject.quote(proof?.toString() ?: "")
        val errorJson = error?.let(JSONObject::quote) ?: "null"
        webView.post {
            webView.evaluateJavascript("window.__keloNfcResolve && window.__keloNfcResolve($id,$json,$errorJson);", null)
        }
    }

    private fun enableNfcReader(operationId: String, subjectDid: String, credentials: IdentityAccessCredentials, keyManager: NfcSigningKeyManager) {
        val adapter = nfcAdapter
        if (adapter == null) {
            completeNfcOperation(operationId, error = "Ce téléphone ne possède pas de lecteur NFC compatible.")
            return
        }
        if (!adapter.isEnabled) {
            completeNfcOperation(operationId, error = "Activez le NFC dans les réglages du téléphone puis réessayez.")
            openNfcSettings()
            return
        }

        activeNfcOperationId = operationId
        activeNfcSubjectDid = subjectDid
        activeNfcCredentials = credentials
        activeNfcKeyManager = keyManager
        adapter.enableReaderMode(
            this,
            { tag -> onNfcTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    private fun onNfcTag(tag: Tag) {
        val operationId = activeNfcOperationId ?: return
        val subjectDid = activeNfcSubjectDid ?: return
        val credentials = activeNfcCredentials ?: return
        val keyManager = activeNfcKeyManager ?: return
        runCatching { nfcAdapter?.disableReaderMode(this) }
        activeNfcOperationId = null
        activeNfcSubjectDid = null
        activeNfcCredentials = null
        activeNfcKeyManager = null

        nfcScope.launch {
            runCatching {
                val identity = IcaoMrtdReader(this@MainActivity).read(tag, credentials)
                NfcProofBuilder(keyManager).build(subjectDid, identity)
            }.onSuccess { proof ->
                val result = JSONObject()
                    .put("payload", proof.payload)
                    .put("signature", proof.signature)
                    .put("algorithm", proof.algorithm)
                    .put("keyId", proof.keyId)
                completeNfcOperation(operationId, result)
            }.onFailure { error ->
                completeNfcOperation(operationId, error = error.message ?: "La lecture NFC a échoué.")
            }
        }
    }

    private fun openNfcSettings() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
        runCatching { startActivity(Intent(action)) }
            .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    private inner class KeloIdNfcBridge {
        @JavascriptInterface
        fun getAvailability(): String {
            val supported = nfcAdapter != null
            return JSONObject()
                .put("supported", supported)
                .put("enabled", supported && nfcAdapter?.isEnabled == true)
                .put("platform", "android")
                .toString()
        }

        @JavascriptInterface
        fun getDeviceSigningKey(): String {
            val key = NfcSigningKeyManager().ensureKey()
            return JSONObject()
                .put("keyId", key.keyId)
                .put("publicKeyPem", key.publicKeyPem)
                .put("algorithm", key.algorithm)
                .put("platform", "android")
                .put("deviceLabel", Build.MANUFACTURER + " " + Build.MODEL)
                .toString()
        }

        @JavascriptInterface
        fun readIdentityCard(requestJson: String): String {
            val request = JSONObject(requestJson)
            val subjectDid = request.optString("subjectDid").trim()
            val issuerCountry = request.optString("issuerCountry").trim().uppercase()
            val documentNumber = request.optString("documentNumber").trim().uppercase()
            val birthDate = request.optString("birthDate").trim()
            val expiryDate = request.optString("expiryDate").trim()
            val can = request.optString("can").trim().takeIf { it.isNotBlank() }

            require(subjectDid.startsWith("did:")) { "Session Kelo ID invalide." }
            require(issuerCountry == "BE" || issuerCountry == "FR") { "Kelo ID prend en charge la Belgique et la France pour cette vérification NFC." }
            require(documentNumber.isNotBlank()) { "Le numéro du document est requis." }
            require(birthDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "La date de naissance est invalide." }
            require(expiryDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "La date d'expiration est invalide." }
            if (issuerCountry == "FR") require(!can.isNullOrBlank()) { "Le CAN de la CNIe française est requis pour la lecture NFC." }

            val operationId = UUID.randomUUID().toString()
            val credentials = IdentityAccessCredentials(
                issuerCountry = issuerCountry,
                documentNumber = documentNumber,
                birthDate = birthDate,
                expiryDate = expiryDate,
                can = can
            )
            enableNfcReader(operationId, subjectDid, credentials, NfcSigningKeyManager())
            return operationId
        }
    }

    private fun initializeFirebaseMessaging() {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    getSharedPreferences("kelo_notifications", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
                    pushFcmTokenToWebView()
                    disableNotificationWorker()
                }
        }
    }

    private fun pushFcmTokenToWebView() {
        if (!::webView.isInitialized) return
        val token = KeloFirebaseMessagingService.currentToken(this) ?: return
        val quotedToken = JSONObject.quote(token)
        webView.post {
            webView.evaluateJavascript("window.KeloIDRegisterFcmToken && window.KeloIDRegisterFcmToken($quotedToken);", null)
        }
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
        val workManager = WorkManager.getInstance(this)
        val immediateRequest = OneTimeWorkRequestBuilder<KeloNotificationWorker>().build()
        workManager.enqueueUniqueWork(KeloNotificationWorker.IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, immediateRequest)
        val periodicRequest = PeriodicWorkRequestBuilder<KeloNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(KeloNotificationWorker.constraints())
            .build()
        workManager.enqueueUniquePeriodicWork(KeloNotificationWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest)
    }

    private fun disableNotificationWorker() {
        WorkManager.getInstance(this).cancelUniqueWork(KeloNotificationWorker.WORK_NAME)
        WorkManager.getInstance(this).cancelUniqueWork(KeloNotificationWorker.IMMEDIATE_WORK_NAME)
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
            if (!did.startsWith("did:")) return
            getSharedPreferences("kelo_notifications", MODE_PRIVATE).edit().putString("subject_did", did).apply()
            if (getSharedPreferences("kelo_notifications", MODE_PRIVATE).getString("fcm_token", null).isNullOrBlank()) scheduleNotificationWorker()
            else disableNotificationWorker()
        }

        @JavascriptInterface
        fun getFcmToken(): String = KeloFirebaseMessagingService.currentToken(this@MainActivity) ?: ""

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
        runCatching { nfcAdapter?.disableReaderMode(this) }
        nfcScope.cancel()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("KeloAndroidNotifications")
            webView.removeJavascriptInterface("KeloIdNfcNative")
            webView.stopLoading(); webView.webChromeClient = null; webView.destroy()
        }
        super.onDestroy()
    }
}

package eu.keloid.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.keloid.nfc.IcaoMrtdReader
import eu.keloid.nfc.IdentityAccessCredentials
import eu.keloid.nfc.IdentityCardReaderRegistry
import eu.keloid.nfc.NfcProofBuilder
import eu.keloid.nfc.NfcSigningKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Première base du nouveau dépôt mobile.
 *
 * Elle conserve le pont NFC déjà éprouvé par Kelo ID afin de ne pas casser la
 * vérification belge. L'interface pourra être remplacée progressivement par
 * des écrans Android natifs sans réécrire le moteur NFC.
 */
class MainActivity : Activity(), NfcAdapter.ReaderCallback {
    companion object {
        private const val KELO_ID_URL = "https://kelo-id.eu/"
        private val ALLOWED_HOSTS = setOf("kelo-id.eu", "www.kelo-id.eu")
    }

    private lateinit var webView: WebView
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signingKeyManager by lazy { NfcSigningKeyManager() }
    private val proofBuilder by lazy { NfcProofBuilder(signingKeyManager) }
    private val readerRegistry by lazy {
        IdentityCardReaderRegistry(listOf(IcaoMrtdReader(this)))
    }

    private data class PendingRead(
        val callbackId: String,
        val subjectDid: String,
        val credentials: IdentityAccessCredentials,
    )

    @Volatile
    private var pendingRead: PendingRead? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString KeloIDAndroid/2.0"
        }

        webView.addJavascriptInterface(KeloNfcJavascriptBridge(), "KeloNativeNfc")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase()
                val allowed = uri.scheme == "https" && host != null && host in ALLOWED_HOSTS
                if (allowed) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val host = Uri.parse(url).host?.lowercase()
                if (host != null && host in ALLOWED_HOSTS) injectNfcBridge()
            }
        }

        webView.loadUrl(KELO_ID_URL)
    }

    override fun onResume() {
        super.onResume()
        if (pendingRead != null) enableReaderMode()
    }

    override fun onPause() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onDestroy() {
        pendingRead = null
        webView.removeJavascriptInterface("KeloNativeNfc")
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun injectNfcBridge() {
        val script = """
            (function () {
              if (window.KeloIdNfc && window.KeloIdNfc.__nativeKeloBridge) return;
              window.__keloNfcCallbacks = window.__keloNfcCallbacks || {};
              window.__keloNfcResolve = function (id, json) {
                const cb = window.__keloNfcCallbacks[id]; if (!cb) return;
                delete window.__keloNfcCallbacks[id];
                try { cb.resolve(JSON.parse(json)); } catch (e) { cb.reject(e); }
              };
              window.__keloNfcReject = function (id, message) {
                const cb = window.__keloNfcCallbacks[id]; if (!cb) return;
                delete window.__keloNfcCallbacks[id]; cb.reject(new Error(message || 'Lecture NFC impossible'));
              };
              window.KeloIdNfc = {
                __nativeKeloBridge: true,
                getAvailability: function () { return Promise.resolve(JSON.parse(KeloNativeNfc.getAvailability())); },
                getDeviceSigningKey: function () { return Promise.resolve(JSON.parse(KeloNativeNfc.getDeviceSigningKey())); },
                openNfcSettings: function () { KeloNativeNfc.openNfcSettings(); return Promise.resolve(); },
                readIdentityCard: function (request) {
                  return new Promise(function (resolve, reject) {
                    const id = 'nfc_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                    window.__keloNfcCallbacks[id] = { resolve: resolve, reject: reject };
                    KeloNativeNfc.startIdentityRead(JSON.stringify(request || {}), id);
                  });
                }
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, this, flags, null)
    }

    override fun onTagDiscovered(tag: Tag) {
        val request = pendingRead ?: return
        pendingRead = null

        scope.launch {
            try {
                val identity = readerRegistry.read(tag, request.credentials)
                val proof = proofBuilder.build(request.subjectDid, identity)
                val json = JSONObject()
                    .put("payload", proof.payload)
                    .put("signature", proof.signature)
                    .put("algorithm", proof.algorithm)
                    .put("keyId", proof.keyId)
                    .toString()
                resolveJavascript(request.callbackId, json)
            } catch (error: Throwable) {
                val raw = error.message.orEmpty()
                val friendly = when {
                    raw.contains("connect", ignoreCase = true) ->
                        "Connexion NFC interrompue. Gardez la carte immobile contre le téléphone jusqu’à la fin de la lecture puis réessayez."
                    raw.contains("tag was lost", ignoreCase = true) || raw.contains("taglost", ignoreCase = true) ->
                        "La carte a été éloignée trop tôt. Maintenez-la contre la zone NFC du téléphone jusqu’à la fin."
                    else -> raw.ifBlank { "Cette carte d’identité NFC ne peut pas être lue par Kelo ID." }
                }
                rejectJavascript(request.callbackId, friendly)
            } finally {
                runOnUiThread {
                    runCatching { nfcAdapter?.disableReaderMode(this@MainActivity) }
                }
            }
        }
    }

    private fun resolveJavascript(callbackId: String, json: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__keloNfcResolve(${JSONObject.quote(callbackId)}, ${JSONObject.quote(json)});",
                null,
            )
        }
    }

    private fun rejectJavascript(callbackId: String, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__keloNfcReject(${JSONObject.quote(callbackId)}, ${JSONObject.quote(message)});",
                null,
            )
        }
    }

    inner class KeloNfcJavascriptBridge {
        @JavascriptInterface
        fun getAvailability(): String {
            val adapter = nfcAdapter
            return JSONObject()
                .put("supported", adapter != null)
                .put("enabled", adapter?.isEnabled == true)
                .put("platform", "android")
                .toString()
        }

        @JavascriptInterface
        fun getDeviceSigningKey(): String {
            val key = signingKeyManager.ensureKey()
            return JSONObject()
                .put("keyId", key.keyId)
                .put("publicKeyPem", key.publicKeyPem)
                .put("algorithm", key.algorithm)
                .put("platform", "android")
                .put("deviceLabel", android.os.Build.MODEL)
                .toString()
        }

        @JavascriptInterface
        fun openNfcSettings() {
            runOnUiThread {
                runCatching { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                    .recoverCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        }

        @JavascriptInterface
        fun startIdentityRead(requestJson: String, callbackId: String) {
            try {
                val request = JSONObject(requestJson)
                val subjectDid = request.optString("subjectDid").trim()
                val issuerCountry = request.optString("issuerCountry").trim().uppercase()
                val documentNumber = request.optString("documentNumber").trim()
                val birthDate = request.optString("birthDate").trim()
                val expiryDate = request.optString("expiryDate").trim()
                val can = request.optString("can").trim().ifBlank { null }

                if (subjectDid.isBlank() || !subjectDid.startsWith("did:")) {
                    throw IllegalArgumentException("Session Kelo ID invalide pour la lecture NFC.")
                }
                if (issuerCountry.length != 2) {
                    throw IllegalArgumentException("Sélectionnez le pays émetteur du document.")
                }

                if (issuerCountry == "FR") {
                    if (can.isNullOrBlank()) {
                        throw IllegalArgumentException("Le CAN imprimé sur la CNIe française est nécessaire pour ouvrir la puce NFC.")
                    }
                } else if (documentNumber.isBlank() || birthDate.isBlank() || expiryDate.isBlank()) {
                    throw IllegalArgumentException(
                        "Numéro du document, date de naissance et date d’expiration sont nécessaires pour ouvrir la puce NFC.",
                    )
                }

                val adapter = nfcAdapter
                    ?: throw IllegalStateException("Cet appareil ne possède pas de lecteur NFC.")
                if (!adapter.isEnabled) {
                    throw IllegalStateException("Le NFC est désactivé. Activez-le dans les réglages du téléphone.")
                }

                pendingRead = PendingRead(
                    callbackId,
                    subjectDid,
                    IdentityAccessCredentials(
                        issuerCountry = issuerCountry,
                        documentNumber = documentNumber,
                        birthDate = birthDate,
                        expiryDate = expiryDate,
                        can = can,
                    ),
                )
                runOnUiThread { enableReaderMode() }
            } catch (error: Throwable) {
                rejectJavascript(
                    callbackId,
                    error.message ?: "Impossible de démarrer la lecture NFC.",
                )
            }
        }
    }
}

package eu.keloid.app

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class KeloQrChallenge(val id: String, val code: String, val secret: String, val expiresAt: String, val payload: String)

internal class KeloQrClient {
    private val http = OkHttpClient()
    private val endpoint = "https://fbtloeehynqobbwcndru.supabase.co/functions/v1/kelo-id-qr"
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun call(body: JSONObject): JSONObject {
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody(json)).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(JSONObject(raw).optString("error", "Opération QR impossible."))
            return JSONObject(raw)
        }
    }

    suspend fun createVerification(session: AtSession): KeloQrChallenge = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val d = call(JSONObject().put("action", "createVerificationChallenge").put("pdsUrl", session.pdsUrl).put("accessJwt", session.accessJwt))
        KeloQrChallenge(d.getString("challengeId"), d.getString("code"), d.getString("secret"), d.getString("expiresAt"), d.getString("payload"))
    }

    suspend fun consumeVerification(code: String, secret: String): VerificationSync = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val d = call(JSONObject().put("action", "consumeVerificationChallenge").put("code", code).put("secret", secret))
        VerificationSync(d.optBoolean("verified"), !d.optBoolean("verified"), d.optString("verificationStatus", "not_started"), d.optString("verificationType", "human"), d.optString("verifiedAt").takeIf { it.isNotBlank() })
    }

    suspend fun createLoginChallenge(): KeloQrChallenge = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val d = call(JSONObject().put("action", "createLoginChallenge"))
        KeloQrChallenge(d.getString("challengeId"), "", d.getString("token"), d.getString("expiresAt"), d.getString("payload"))
    }
}

@Composable
internal fun KeloQrScreen(session: AtSession?, onVerified: (VerificationSync) -> Unit, onBack: () -> Unit) {
    val client = remember { KeloQrClient() }
    val scope = rememberCoroutineScope()
    var challenge by remember { mutableStateOf<KeloQrChallenge?>(null) }
    var code by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
        val scannedCode = uri?.getQueryParameter("code")
        val scannedSecret = uri?.getQueryParameter("secret")
        if (scannedCode != null && scannedSecret != null) {
            code = scannedCode; secret = scannedSecret
            scope.launch {
                loading = true; message = null
                runCatching { client.consumeVerification(scannedCode, scannedSecret) }
                    .onSuccess { onVerified(it); message = "Code QR validé. Statut synchronisé avec Kelo ID." }
                    .onFailure { message = it.message }
                loading = false
            }
        } else message = "QR Kelo ID non reconnu."
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KeloBrandHeader("Connexion et vérification QR")
        Text("Code ou QR code", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Utilisez un code à usage unique ou scannez le QR affiché par Kelo ID/Kelo Social. Le statut final reste celui de Supabase.", color = KeloMuted)
        if (session != null) {
            Button(onClick = { scope.launch { loading = true; message = null; runCatching { client.createVerification(session) }.onSuccess { challenge = it; code = it.code; secret = it.secret }.onFailure { message = it.message }; loading = false } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                if (loading) CircularProgressIndicator() else Text("Générer mon QR de vérification")
            }
        }
        challenge?.let { ch ->
            KeloCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Présentez ce QR code", fontWeight = FontWeight.Bold)
                    QrBitmap(payload = ch.payload)?.let { Image(it.asImageBitmap(), "QR Kelo ID", modifier = Modifier.size(230.dp)) }
                    Text("Code : ${ch.code}", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Valable 10 minutes.", color = KeloMuted)
                }
            }
        }
        KeloCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Valider un code", fontWeight = FontWeight.Bold)
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("Code à 6 chiffres") }, singleLine = true)
                OutlinedTextField(secret, { secret = it.trim() }, modifier = Modifier.fillMaxWidth(), label = { Text("Clé QR") }, singleLine = true)
                Button(onClick = { scope.launch { loading = true; message = null; runCatching { client.consumeVerification(code, secret) }.onSuccess { onVerified(it); message = "Vérification synchronisée." }.onFailure { message = it.message }; loading = false } }, enabled = !loading && code.length == 6 && secret.length >= 16, modifier = Modifier.fillMaxWidth()) { Text("Valider") }
                OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scannez votre QR Kelo ID").setBeepEnabled(true)) }, modifier = Modifier.fillMaxWidth()) { Text("Scanner un QR code") }
            }
        }
        message?.let { Text(it, color = if (it.contains("valid", true) || it.contains("synchron", true)) KeloPurple else androidx.compose.material3.MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("Retour") }
        }
    }
}

private fun QrBitmap(payload: String): Bitmap? = runCatching { BarcodeEncoder().encodeBitmap(payload, com.google.zxing.BarcodeFormat.QR_CODE, 700, 700) }.getOrNull()

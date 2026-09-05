package eu.keloid.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun NativeVerificationScreen(
    session: AtSession,
    method: String,
    api: VerificationApiClient,
    onFinished: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var nationality by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf<String?>(null) }
    var challenge by remember { mutableStateOf<String?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var photoUploaded by remember { mutableStateOf(false) }
    var videoUploaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri = pendingCapture
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (ok) videoUri = pendingCapture
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "La caméra est nécessaire pour cette vérification."
    }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "Le microphone est nécessaire pour la vidéo de vérification."
    }

    fun ensureCamera(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        requestCamera.launch(Manifest.permission.CAMERA)
        return false
    }
    fun ensureMic(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (method == "automatic") "Vérification automatique" else "Vérification manuelle",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text("Les documents servent uniquement à la vérification. Kelo Social reçoit le statut, pas les images d'identité.")

        if (requestId == null) {
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prénom") },
                singleLine = true
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nom") },
                singleLine = true
            )
            OutlinedTextField(
                value = birthDate,
                onValueChange = { birthDate = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Date de naissance (AAAA-MM-JJ)") },
                singleLine = true
            )
            OutlinedTextField(
                value = nationality,
                onValueChange = { nationality = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nationalité (BE, FR, ...)") },
                singleLine = true
            )
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        message = null
                        runCatching { api.startRequest(session, method, firstName, lastName, birthDate, nationality) }
                            .onSuccess {
                                requestId = it.requestId
                                challenge = it.livenessChallenge
                            }
                            .onFailure { message = it.message }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && firstName.isNotBlank() && lastName.isNotBlank() && birthDate.length == 10 && nationality.isNotBlank()
            ) { if (loading) CircularProgressIndicator() else Text("Créer la demande") }
        } else {
            challenge?.let { instruction ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Contrôle vidéo", fontWeight = FontWeight.SemiBold)
                        Text(instruction)
                    }
                }
            }

            EvidenceCard(
                title = "1. Photo du document",
                ready = photoUri != null,
                uploaded = photoUploaded,
                captureLabel = "Prendre la photo",
                onCapture = {
                    if (ensureCamera()) {
                        pendingCapture = createTempUri(context, "document", ".jpg")
                        takePhoto.launch(pendingCapture!!)
                    }
                },
                onUpload = upload@{
                    val uri = photoUri ?: return@upload
                    scope.launch {
                        loading = true
                        message = null
                        runCatching { api.uploadEvidence(session, requestId!!, "document_front", uri, "image/jpeg") }
                            .onSuccess { photoUploaded = true }
                            .onFailure { message = it.message }
                        loading = false
                    }
                }
            )

            EvidenceCard(
                title = "2. Vidéo de vérification",
                ready = videoUri != null,
                uploaded = videoUploaded,
                captureLabel = "Filmer la vidéo",
                onCapture = {
                    if (ensureCamera() && ensureMic()) {
                        pendingCapture = createTempUri(context, "liveness", ".mp4")
                        takeVideo.launch(pendingCapture!!)
                    }
                },
                onUpload = upload@{
                    val uri = videoUri ?: return@upload
                    scope.launch {
                        loading = true
                        message = null
                        runCatching { api.uploadEvidence(session, requestId!!, "liveness_video", uri, "video/mp4") }
                            .onSuccess { videoUploaded = true }
                            .onFailure { message = it.message }
                        loading = false
                    }
                }
            )

            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        message = null
                        runCatching { api.finalizeRequest(session, requestId!!) }
                            .onSuccess {
                                message = if (method == "automatic")
                                    "Vérification envoyée. Les contrôles serveur vont traiter la demande."
                                else "Vérification envoyée pour validation humaine."
                                onFinished()
                            }
                            .onFailure { message = it.message }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && photoUploaded && videoUploaded
            ) { if (loading) CircularProgressIndicator() else Text("Envoyer la vérification") }
        }

        message?.let { currentMessage ->
            Text(
                currentMessage,
                color = if (currentMessage.contains("envoyée", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        TextButton(onClick = onBack) { Text("Retour") }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun EvidenceCard(
    title: String,
    ready: Boolean,
    uploaded: Boolean,
    captureLabel: String,
    onCapture: () -> Unit,
    onUpload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(when { uploaded -> "Envoyé"; ready -> "Prêt à envoyer"; else -> "À capturer" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCapture) { Text(captureLabel) }
                if (ready && !uploaded) Button(onClick = onUpload) { Text("Envoyer") }
            }
        }
    }
}

@Composable
internal fun QrLinkButton(
    session: AtSession,
    client: AtProtoSyncClient,
    onLinked: (VerificationSync) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents.orEmpty().trim()
        val code = Regex("(?:^|[^0-9])([0-9]{6})(?:[^0-9]|$)").find(raw)?.groupValues?.getOrNull(1)
        if (code == null) {
            if (raw.isNotBlank()) onError("Ce QR code ne contient pas un code Kelo ID valide.")
        } else {
            scope.launch {
                runCatching { client.consumeLinkCode(session, code) }
                    .onSuccess(onLinked)
                    .onFailure { onError(it.message ?: "Synchronisation QR impossible.") }
            }
        }
    }
    OutlinedButton(
        onClick = {
            launcher.launch(
                ScanOptions()
                    .setPrompt("Scannez le QR Kelo Social / Kelo ID")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Scanner un QR code Kelo") }
}

private fun createTempUri(context: Context, prefix: String, suffix: String): Uri {
    val dir = File(context.cacheDir, "verification-captures").apply { mkdirs() }
    val file = File.createTempFile(prefix, suffix, dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

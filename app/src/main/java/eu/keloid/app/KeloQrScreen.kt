package eu.keloid.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

private fun extractLoginChallenge(raw: String): String? {
    val value = raw.trim()
    return runCatching {
        val url = java.net.URI(value)
        val query = url.rawQuery.orEmpty()
        query.split('&').firstNotNullOfOrNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == "challenge") parts[1] else null
        }?.let(java.net.URLDecoder::decode)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: value.takeIf { it.matches(Regex("[A-Fa-f0-9-]{20,100}")) }
}

@Composable
internal fun KeloQrScreen(
    session: AtSession,
    onSynced: (VerificationSync) -> Unit,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Scannez le QR affiché sur Kelo Social pour connecter ce compte AT Protocol.") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val client = remember(context) { AtProtoSyncClient(context) }
    val scope = rememberCoroutineScope()

    fun approve(raw: String) {
        val challenge = extractLoginChallenge(raw)
        if (challenge == null) {
            error = "Ce QR Kelo Social n’est pas reconnu."
            return
        }
        loading = true
        error = null
        message = "Connexion sécurisée en cours…"
        scope.launch {
            runCatching { client.approveKeloSocialLoginQr(session, challenge) }
                .onSuccess {
                    message = "Connexion autorisée. Retournez sur Kelo Social : la connexion se termine automatiquement."
                    runCatching { client.sync(session) }.onSuccess(onSynced)
                }
                .onFailure { error = it.message ?: "Impossible d’autoriser la connexion." }
            loading = false
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(::approve)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KeloBrandHeader("Connexion Kelo Social")
        Text("Connexion par QR code", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = KeloInk)
        Text(
            "Votre compte AT Protocol déjà vérifié autorise la connexion sur un PC ou une tablette. Aucun mot de passe, code à saisir ou code à générer n’est utilisé.",
            color = KeloMuted
        )

        KeloGradientCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kelo Social", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Compte : @${session.handle}", color = Color.White.copy(alpha = .9f))
                Text("Identité vérifiée", color = Color.White.copy(alpha = .9f))
            }
        }

        Button(
            onClick = {
                scanner.launch(
                    ScanOptions().apply {
                        setPrompt("Cadrez le QR affiché sur Kelo Social")
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) { Text("Scanner le QR de Kelo Social") }

        if (loading) CircularProgressIndicator()
        Text(message, color = KeloMuted)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        TextButton(onClick = onBack, enabled = !loading) { Text("Retour") }
        Spacer(Modifier.height(20.dp))
    }
}

package eu.keloid.app

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun KeloQrScreen(
    session: AtSession,
    onSynced: (VerificationSync) -> Unit,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val client = remember(context) { AtProtoSyncClient(context) }
    val scope = rememberCoroutineScope()

    fun generate() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { client.createLinkCode(session) }
                .onSuccess { sync ->
                    code = sync.linkCode
                    expiresAt = sync.linkCodeExpiresAt
                    onSynced(sync)
                }
                .onFailure { error = it.message ?: "Impossible de générer le code." }
            loading = false
        }
    }

    LaunchedEffect(session.did) {
        generate()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KeloBrandHeader("Connexion et liaison sécurisées")
        Text(
            "Code Kelo ID",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = KeloInk
        )
        Text(
            "Utilisez ce code pour lier Kelo ID à votre compte Kelo Social. Le code est temporaire.",
            color = KeloMuted
        )
        KeloGradientCard {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    code ?: "••••••",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black
                )
                expiresAt?.let {
                    Text(
                        "Expire : $it",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f)
                    )
                }
            }
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { generate() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            Text("Générer un nouveau code")
        }
        Text("Compte : @${session.handle}", color = KeloMuted)
        TextButton(onClick = onBack) { Text("Retour") }
        Spacer(Modifier.height(20.dp))
    }
}

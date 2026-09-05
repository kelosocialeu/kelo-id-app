package eu.keloid.app

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                KeloIdApp(
                    client = remember { AtProtoSyncClient(this) },
                    incomingUri = intent?.data,
                    hasNfc = nfcAdapter != null,
                    isNfcEnabled = nfcAdapter?.isEnabled == true,
                    onOpenNfcSettings = ::openNfcSettings,
                    onOpenWebsite = ::openWebsite
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun openNfcSettings() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC
        else Settings.ACTION_NFC_SETTINGS
        runCatching { startActivity(Intent(action)) }
            .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    private fun openWebsite(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun KeloIdApp(
    client: AtProtoSyncClient,
    incomingUri: Uri?,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(client.loadSession()) }
    var sync by remember { mutableStateOf<VerificationSync?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session?.did) {
        val current = session ?: return@LaunchedEffect
        loading = true
        runCatching { client.sync(current) }
            .onSuccess { sync = it }
            .onFailure { error = it.message }
        loading = false
    }

    Scaffold { padding ->
        if (session == null) {
            LoginScreen(
                modifier = Modifier.padding(padding),
                loading = loading,
                error = error,
                onLogin = { identifier, password, pdsUrl ->
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { client.login(identifier, password, pdsUrl) }
                            .onSuccess {
                                session = it
                                sync = runCatching { client.sync(it) }.getOrNull()
                            }
                            .onFailure { error = it.message ?: "Connexion impossible." }
                        loading = false
                    }
                }
            )
        } else {
            DashboardScreen(
                modifier = Modifier.padding(padding),
                session = session!!,
                sync = sync,
                loading = loading,
                incomingUri = incomingUri,
                hasNfc = hasNfc,
                isNfcEnabled = isNfcEnabled,
                error = error,
                onRefresh = {
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { client.sync(session!!) }
                            .onSuccess { sync = it }
                            .onFailure { error = it.message }
                        loading = false
                    }
                },
                onCreateLinkCode = {
                    scope.launch {
                        loading = true
                        runCatching { client.createLinkCode(session!!) }
                            .onSuccess { sync = it }
                            .onFailure { error = it.message }
                        loading = false
                    }
                },
                onLogout = {
                    client.clearSession()
                    session = null
                    sync = null
                },
                onOpenNfcSettings = onOpenNfcSettings,
                onOpenWebsite = onOpenWebsite
            )
        }
    }
}

@Composable
private fun LoginScreen(
    modifier: Modifier,
    loading: Boolean,
    error: String?,
    onLogin: (String, String, String) -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pdsUrl by remember { mutableStateOf("https://pds.kelosocial.eu") }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Kelo ID", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Connectez-vous avec votre compte AT Protocol. Kelo ID vérifie ensuite automatiquement si votre compte est déjà vérifié.")

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text("Identifiant ou handle") },
            singleLine = true
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe ou mot de passe d'application") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = pdsUrl,
            onValueChange = { pdsUrl = it },
            label = { Text("Serveur PDS") },
            singleLine = true
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && identifier.isNotBlank() && password.isNotBlank(),
            onClick = { onLogin(identifier, password, pdsUrl) }
        ) {
            if (loading) CircularProgressIndicator() else Text("Se connecter")
        }

        Text(
            "Le mot de passe est envoyé directement au PDS AT Protocol. Kelo ID ne l'enregistre pas dans Supabase.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DashboardScreen(
    modifier: Modifier,
    session: AtSession,
    sync: VerificationSync?,
    loading: Boolean,
    incomingUri: Uri?,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onCreateLinkCode: () -> Unit,
    onLogout: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Kelo ID", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("@${session.handle}", style = MaterialTheme.typography.titleMedium)
        Text(session.did, style = MaterialTheme.typography.bodySmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("État de la vérification", fontWeight = FontWeight.SemiBold)
                when {
                    loading && sync == null -> CircularProgressIndicator()
                    sync?.verified == true -> {
                        Text("Compte vérifié")
                        Text("Kelo ID et Supabase reconnaissent déjà ce compte comme vérifié.")
                    }
                    else -> {
                        Text("Vérification nécessaire")
                        Text("Ce compte n'est pas encore marqué comme vérifié dans Kelo ID.")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenWebsite("https://kelo-id.eu/") }
                        ) { Text("Commencer la vérification") }
                    }
                }
            }
        }

        sync?.linkCode?.let { code ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Code de synchronisation Kelo Social", fontWeight = FontWeight.SemiBold)
                    Text(code, style = MaterialTheme.typography.headlineMedium)
                    Text("Valable environ 10 minutes. Il relie ce même DID à Kelo Social sans transmettre vos documents d'identité.")
                }
            }
        }

        Button(Modifier.fillMaxWidth(), onClick = onCreateLinkCode, enabled = !loading) {
            Text("Générer un code pour Kelo Social")
        }
        OutlinedButton(Modifier.fillMaxWidth(), onClick = onRefresh, enabled = !loading) {
            Text("Actualiser l'état")
        }

        Text("Méthodes de vérification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        VerificationCard("Vérification automatique", "Document + contrôle vidéo.") {
            onOpenWebsite("https://kelo-id.eu/")
        }
        VerificationCard("Vérification manuelle", "Envoi pour validation humaine.") {
            onOpenWebsite("https://kelo-id.eu/")
        }
        VerificationCard(
            "Carte d'identité NFC",
            when {
                !hasNfc -> "NFC non disponible sur cet appareil."
                !isNfcEnabled -> "NFC disponible mais désactivé."
                else -> "NFC prêt pour la lecture sécurisée de la carte."
            },
            enabled = hasNfc
        ) {
            if (!isNfcEnabled) onOpenNfcSettings()
        }

        incomingUri?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Lien reçu", fontWeight = FontWeight.SemiBold)
                    Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onOpenWebsite("https://kelosocial.eu/") }) { Text("Kelo Social") }
            TextButton(onClick = onLogout) { Text("Déconnexion") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VerificationCard(title: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description)
            Button(Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick) { Text("Continuer") }
        }
    }
}

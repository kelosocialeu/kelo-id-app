package eu.keloid.app

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.keloid.nfc.IcaoMrtdReader
import eu.keloid.nfc.IdentityAccessCredentials
import eu.keloid.nfc.NfcProofBuilder
import eu.keloid.nfc.NfcSigningKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nfcStatus = mutableStateOf<String?>(null)
    private val nfcBusy = mutableStateOf(false)
    private val nfcSync = mutableStateOf<VerificationSync?>(null)
    private var activeNfcCredentials: IdentityAccessCredentials? = null
    private var activeNfcSession: AtSession? = null
    private var activeNfcApi: VerificationApiClient? = null
    private var activeNfcRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeloIdTheme {
                KeloIdApp(
                    client = remember { AtProtoSyncClient(this) },
                    verificationApi = remember { VerificationApiClient(this) },
                    incomingUri = intent?.data,
                    hasNfc = nfcAdapter != null,
                    isNfcEnabled = nfcAdapter?.isEnabled == true,
                    nfcStatus = nfcStatus.value,
                    nfcBusy = nfcBusy.value,
                    syncFromNfc = nfcSync.value,
                    onStartNfc = ::startNfcVerification,
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

    override fun onDestroy() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startNfcVerification(
        session: AtSession,
        api: VerificationApiClient,
        credentials: IdentityAccessCredentials
    ) {
        val adapter = nfcAdapter
        if (adapter == null) {
            nfcStatus.value = "Ce téléphone ne possède pas de lecteur NFC compatible."
            return
        }
        if (!adapter.isEnabled) {
            nfcStatus.value = "Activez le NFC puis réessayez."
            openNfcSettings()
            return
        }

        nfcBusy.value = true
        nfcStatus.value = "Préparation de la vérification NFC…"
        activityScope.launch {
            runCatching {
                val request = api.startRequest(
                    session = session,
                    method = "nfc",
                    firstName = "",
                    lastName = "",
                    birthDate = credentials.birthDate,
                    nationality = credentials.issuerCountry
                )
                val keyManager = NfcSigningKeyManager()
                val key = keyManager.ensureKey()
                api.registerNfcDeviceKey(session, key.keyId, key.publicKeyPem, key.algorithm)
                activeNfcCredentials = credentials
                activeNfcSession = session
                activeNfcApi = api
                activeNfcRequestId = request.requestId
                nfcStatus.value = "Approchez la carte du téléphone et gardez-la immobile."
                adapter.enableReaderMode(
                    this@MainActivity,
                    { tag -> onNfcTag(tag, keyManager) },
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null
                )
            }.onFailure {
                nfcBusy.value = false
                nfcStatus.value = it.message ?: "Impossible de préparer la lecture NFC."
            }
        }
    }

    private fun onNfcTag(tag: Tag, keyManager: NfcSigningKeyManager) {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        val credentials = activeNfcCredentials ?: return
        val session = activeNfcSession ?: return
        val api = activeNfcApi ?: return
        val requestId = activeNfcRequestId ?: return

        activityScope.launch {
            nfcStatus.value = "Lecture sécurisée de la puce…"
            runCatching {
                val identity = IcaoMrtdReader(this@MainActivity).read(tag, credentials)
                val proof = NfcProofBuilder(keyManager).build(session.did, identity)
                api.submitNfcProof(
                    session = session,
                    requestId = requestId,
                    payload = proof.payload,
                    signature = proof.signature,
                    keyId = proof.keyId,
                    algorithm = proof.algorithm
                )
                nfcStatus.value = "Lecture NFC validée et envoyée à Kelo ID."
                nfcSync.value = runCatching { AtProtoSyncClient(this@MainActivity).sync(session) }.getOrNull()
            }.onFailure {
                nfcStatus.value = it.message ?: "La lecture NFC a échoué."
            }
            nfcBusy.value = false
        }
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
    verificationApi: VerificationApiClient,
    incomingUri: Uri?,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    nfcStatus: String?,
    nfcBusy: Boolean,
    syncFromNfc: VerificationSync?,
    onStartNfc: (AtSession, VerificationApiClient, IdentityAccessCredentials) -> Unit,
    onOpenNfcSettings: () -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(client.loadSession()) }
    var sync by remember { mutableStateOf<VerificationSync?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("dashboard") }

    LaunchedEffect(session?.did) {
        val current = session ?: return@LaunchedEffect
        loading = true
        runCatching { client.sync(current) }
            .onSuccess { sync = it }
            .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(syncFromNfc) {
        if (syncFromNfc != null) sync = syncFromNfc
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        KeloPage(modifier = Modifier.padding(padding)) {
            val current = session
            if (current == null) {
                LoginScreen(
                    loading = loading,
                    error = error,
                    onLogin = { identifier, password ->
                        scope.launch {
                            loading = true
                            error = null
                            runCatching { client.login(identifier, password) }
                                .onSuccess { loggedIn ->
                                    session = loggedIn
                                    sync = runCatching { client.sync(loggedIn) }.getOrNull()
                                }
                                .onFailure { error = it.message ?: "Connexion impossible." }
                            loading = false
                        }
                    }
                )
            } else {
                when (page) {
                    "automatic", "manual" -> NativeVerificationScreen(
                        session = current,
                        method = page,
                        api = verificationApi,
                        onFinished = {
                            scope.launch {
                                sync = runCatching { client.sync(current) }.getOrNull()
                                page = "dashboard"
                            }
                        },
                        onBack = { page = "dashboard" }
                    )

                    "nfc" -> NfcVerificationScreen(
                        status = nfcStatus,
                        busy = nfcBusy,
                        onStart = { credentials -> onStartNfc(current, verificationApi, credentials) },
                        onBack = {
                            scope.launch {
                                sync = runCatching { client.sync(current) }.getOrNull() ?: sync
                                page = "dashboard"
                            }
                        }
                    )

                    else -> DashboardScreen(
                        session = current,
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
                                runCatching { client.sync(current) }
                                    .onSuccess { sync = it }
                                    .onFailure { error = it.message }
                                loading = false
                            }
                        },
                        onCreateLinkCode = {
                            scope.launch {
                                loading = true
                                runCatching { client.createLinkCode(current) }
                                    .onSuccess { sync = it }
                                    .onFailure { error = it.message }
                                loading = false
                            }
                        },
                        onQrLinked = { sync = it },
                        onQrError = { error = it },
                        onAutomatic = { page = "automatic" },
                        onManual = { page = "manual" },
                        onNfc = {
                            if (!isNfcEnabled) onOpenNfcSettings() else page = "nfc"
                        },
                        onLogout = {
                            client.clearSession()
                            session = null
                            sync = null
                        },
                        onOpenWebsite = onOpenWebsite
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    loading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        KeloBrandHeader(subtitle = "Votre identité, vérifiée simplement")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Vérifiez votre identité. Gardez le contrôle.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = KeloInk
        )
        Text(
            "Connectez-vous avec votre compte AT Protocol. Kelo ID détecte automatiquement votre PDS, quel que soit le serveur utilisé.",
            color = KeloMuted,
            style = MaterialTheme.typography.bodyLarge
        )

        KeloCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connexion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Handle ou DID") },
                    placeholder = { Text("ex. utilisateur.kelosocial.eu") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mot de passe ou mot de passe d'application") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { onLogin(identifier, password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && identifier.isNotBlank() && password.isNotBlank()
                ) {
                    if (loading) CircularProgressIndicator() else Text("Se connecter")
                }
                Text(
                    "Le mot de passe est envoyé uniquement au PDS détecté. Il n'est jamais enregistré dans Supabase.",
                    color = KeloMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        KeloGradientCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Une vérification, partout", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Le statut Kelo ID est synchronisé avec Kelo Social sans transmettre vos documents d'identité.",
                    color = Color.White.copy(alpha = 0.92f)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun DashboardScreen(
    session: AtSession,
    sync: VerificationSync?,
    loading: Boolean,
    incomingUri: Uri?,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onCreateLinkCode: () -> Unit,
    onQrLinked: (VerificationSync) -> Unit,
    onQrError: (String) -> Unit,
    onAutomatic: () -> Unit,
    onManual: () -> Unit,
    onNfc: () -> Unit,
    onLogout: () -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val qrClient = remember { AtProtoSyncClient(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        KeloBrandHeader(subtitle = "@${session.handle}")

        KeloCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Votre compte", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(session.did, color = KeloMuted, style = MaterialTheme.typography.bodySmall)
                Text("PDS détecté : ${session.pdsUrl}", color = KeloMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (sync?.verified == true) {
            KeloGradientCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Compte vérifié", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Kelo ID et Kelo Social reconnaissent ce DID comme vérifié.", color = Color.White.copy(alpha = 0.92f))
                }
            }
        } else {
            KeloCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("État de la vérification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    when {
                        loading && sync == null -> CircularProgressIndicator()
                        sync?.status == "pending" -> {
                            Text("Vérification en cours", fontWeight = FontWeight.SemiBold)
                            Text("Votre demande est déjà enregistrée et en attente de traitement.", color = KeloMuted)
                        }
                        else -> {
                            Text("Vérification nécessaire", fontWeight = FontWeight.SemiBold)
                            Text("Choisissez une méthode ci-dessous pour continuer.", color = KeloMuted)
                        }
                    }
                }
            }
        }

        sync?.linkCode?.let { code ->
            KeloCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Code Kelo Social", fontWeight = FontWeight.Bold)
                    Text(code, style = MaterialTheme.typography.headlineMedium, color = KeloPurple, fontWeight = FontWeight.Black)
                    Text("Code temporaire pour relier le même DID sans transmettre les documents.", color = KeloMuted)
                }
            }
        }

        Button(onClick = onCreateLinkCode, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
            Text("Générer un code Kelo Social")
        }
        QrLinkButton(session = session, client = qrClient, onLinked = onQrLinked, onError = onQrError)
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
            Text("Actualiser mon statut")
        }

        Text("Choisir une méthode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        VerificationCard(
            title = "Vérification automatique",
            description = "Document d'identité + contrôle vidéo. Les contrôles serveur analysent la demande avant validation.",
            onClick = onAutomatic
        )
        VerificationCard(
            title = "Vérification manuelle",
            description = "Document et vidéo transmis pour une validation humaine.",
            onClick = onManual
        )
        VerificationCard(
            title = "Carte d'identité NFC",
            description = when {
                !hasNfc -> "Le NFC n'est pas disponible sur ce téléphone."
                !isNfcEnabled -> "Le NFC est disponible mais désactivé."
                else -> "Lecture sécurisée de la puce de votre carte d'identité."
            },
            enabled = hasNfc,
            onClick = onNfc
        )

        incomingUri?.let {
            KeloCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lien Kelo reçu", fontWeight = FontWeight.Bold)
                    Text(it.toString(), color = KeloMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpenWebsite("https://kelo-id.eu/") }) { Text("Site Kelo ID") }
            TextButton(onClick = { onOpenWebsite("https://kelosocial.eu/") }) { Text("Kelo Social") }
        }
        TextButton(onClick = onLogout) { Text("Se déconnecter") }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun VerificationCard(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    KeloCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, color = KeloMuted)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                Text("Continuer")
            }
        }
    }
}

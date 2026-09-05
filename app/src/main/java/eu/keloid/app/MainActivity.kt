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
            MaterialTheme {
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
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_NFC
        } else {
            Settings.ACTION_NFC_SETTINGS
        }
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

    Scaffold { padding ->
        val current = session
        if (current == null) {
            LoginScreen(
                modifier = Modifier.padding(padding),
                loading = loading,
                error = error,
                onLogin = { identifier, password, pdsUrl ->
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { client.login(identifier, password, pdsUrl) }
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
                    modifier = Modifier.padding(padding),
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
        Spacer(modifier = Modifier.height(12.dp))
        Text("Kelo ID", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Connectez-vous avec votre compte AT Protocol. Kelo ID vérifie automatiquement l'état du même DID dans Supabase.")
        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Identifiant ou handle") },
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
        OutlinedTextField(
            value = pdsUrl,
            onValueChange = { pdsUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Serveur PDS") },
            singleLine = true
        )
        error?.let { currentError -> Text(currentError, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { onLogin(identifier, password, pdsUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && identifier.isNotBlank() && password.isNotBlank()
        ) {
            if (loading) CircularProgressIndicator() else Text("Se connecter")
        }
        Text(
            "Le mot de passe est envoyé au PDS AT Protocol et n'est pas enregistré dans Supabase.",
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
        modifier = modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Kelo ID", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("@${session.handle}", style = MaterialTheme.typography.titleMedium)
        Text(session.did, style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("État de la vérification", fontWeight = FontWeight.SemiBold)
                when {
                    loading && sync == null -> CircularProgressIndicator()
                    sync?.verified == true -> {
                        Text("Compte vérifié")
                        Text("Le même DID est reconnu comme vérifié par Kelo ID.")
                    }
                    sync?.status == "pending" -> {
                        Text("Vérification en cours")
                        Text("Une demande est déjà enregistrée dans Kelo ID.")
                    }
                    else -> {
                        Text("Vérification nécessaire")
                        Text("Choisissez une méthode ci-dessous.")
                    }
                }
            }
        }

        sync?.linkCode?.let { code ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Code Kelo Social", fontWeight = FontWeight.SemiBold)
                    Text(code, style = MaterialTheme.typography.headlineMedium)
                    Text("Ce code temporaire relie le même DID sans transmettre les documents.")
                }
            }
        }

        Button(
            onClick = onCreateLinkCode,
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) { Text("Générer un code pour Kelo Social") }

        QrLinkButton(
            session = session,
            client = qrClient,
            onLinked = onQrLinked,
            onError = onQrError
        )

        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) { Text("Actualiser l'état") }

        Text("Méthodes de vérification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        VerificationCard(
            title = "Vérification automatique",
            description = "Photo du document et vidéo de contrôle dans l'application.",
            onClick = onAutomatic
        )
        VerificationCard(
            title = "Vérification manuelle",
            description = "Photo et vidéo envoyées pour validation humaine.",
            onClick = onManual
        )
        VerificationCard(
            title = "Carte d'identité NFC",
            description = when {
                !hasNfc -> "NFC non disponible sur cet appareil."
                !isNfcEnabled -> "NFC disponible mais désactivé."
                else -> "Lecture sécurisée de la puce disponible."
            },
            enabled = hasNfc,
            onClick = onNfc
        )

        incomingUri?.let { uri ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Lien reçu", fontWeight = FontWeight.SemiBold)
                    Text(uri.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        error?.let { currentError -> Text(currentError, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onOpenWebsite("https://kelosocial.eu/") }) { Text("Kelo Social") }
            TextButton(onClick = onLogout) { Text("Déconnexion") }
        }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description)
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            ) { Text("Continuer") }
        }
    }
}

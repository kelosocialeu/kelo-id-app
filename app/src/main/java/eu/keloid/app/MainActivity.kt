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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
        setContent { KeloIdTheme { KeloIdApp(remember { AtProtoSyncClient(this) }, remember { VerificationApiClient(this) }, intent?.data, nfcAdapter != null, nfcAdapter?.isEnabled == true, nfcStatus.value, nfcBusy.value, nfcSync.value, ::startNfcVerification, ::openNfcSettings, ::openWebsite) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }
    override fun onDestroy() { runCatching { nfcAdapter?.disableReaderMode(this) }; activityScope.cancel(); super.onDestroy() }

    private fun startNfcVerification(session: AtSession, api: VerificationApiClient, credentials: IdentityAccessCredentials) {
        val adapter = nfcAdapter ?: run { nfcStatus.value = "Ce téléphone ne possède pas de lecteur NFC compatible."; return }
        if (!adapter.isEnabled) { nfcStatus.value = "Activez le NFC puis réessayez."; openNfcSettings(); return }
        nfcBusy.value = true
        activityScope.launch {
            runCatching {
                val request = api.startRequest(session, "nfc", "", "", credentials.birthDate, credentials.issuerCountry)
                val keyManager = NfcSigningKeyManager(); val key = keyManager.ensureKey()
                api.registerNfcDeviceKey(session, key.keyId, key.publicKeyPem, key.algorithm)
                activeNfcCredentials = credentials; activeNfcSession = session; activeNfcApi = api; activeNfcRequestId = request.requestId
                nfcStatus.value = "Approchez la carte du téléphone et gardez-la immobile."
                adapter.enableReaderMode(this@MainActivity, { tag -> onNfcTag(tag, keyManager) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            }.onFailure { nfcBusy.value = false; nfcStatus.value = it.message ?: "Impossible de préparer la lecture NFC." }
        }
    }
    private fun onNfcTag(tag: Tag, keyManager: NfcSigningKeyManager) {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        val credentials = activeNfcCredentials ?: return; val session = activeNfcSession ?: return; val api = activeNfcApi ?: return; val requestId = activeNfcRequestId ?: return
        activityScope.launch {
            nfcStatus.value = "Lecture sécurisée de la puce…"
            runCatching {
                val identity = IcaoMrtdReader(this@MainActivity).read(tag, credentials)
                val proof = NfcProofBuilder(keyManager).build(session.did, identity)
                api.submitNfcProof(session, requestId, proof.payload, proof.signature, proof.keyId, proof.algorithm)
                nfcStatus.value = "Lecture NFC envoyée à Kelo ID. Validation serveur en cours."
                nfcSync.value = runCatching { AtProtoSyncClient(this@MainActivity).sync(session) }.getOrNull()
            }.onFailure { nfcStatus.value = it.message ?: "La lecture NFC a échoué." }
            nfcBusy.value = false
        }
    }
    private fun openNfcSettings() { val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS; runCatching { startActivity(Intent(action)) }.onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    private fun openWebsite(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun KeloIdApp(client: AtProtoSyncClient, verificationApi: VerificationApiClient, incomingUri: Uri?, hasNfc: Boolean, isNfcEnabled: Boolean, nfcStatus: String?, nfcBusy: Boolean, syncFromNfc: VerificationSync?, onStartNfc: (AtSession, VerificationApiClient, IdentityAccessCredentials) -> Unit, onOpenNfcSettings: () -> Unit, onOpenWebsite: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(client.loadSession()) }
    var sync by remember { mutableStateOf<VerificationSync?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("home") }

    LaunchedEffect(session?.did) { val current = session ?: return@LaunchedEffect; loading = true; runCatching { client.sync(current) }.onSuccess { sync = it }.onFailure { error = it.message }; loading = false }
    LaunchedEffect(syncFromNfc) { if (syncFromNfc != null) sync = syncFromNfc }

    Scaffold(containerColor = Color.Transparent, bottomBar = {
        if (session != null) NavigationBar(modifier = Modifier.navigationBarsPadding()) {
            NavigationBarItem(selected = page == "home", onClick = { page = "home" }, icon = { Text("⌂") }, label = { Text("Accueil") })
            NavigationBarItem(selected = page == "verify", onClick = { page = "verify" }, icon = { Text("✓") }, label = { Text("Vérifier") })
            NavigationBarItem(selected = page == "qr", onClick = { page = "qr" }, icon = { Text("▣") }, label = { Text("QR code") })
            NavigationBarItem(selected = page == "account", onClick = { page = "account" }, icon = { Text("●") }, label = { Text("Compte") })
        }
    }) { padding ->
        KeloPage(Modifier.padding(padding)) {
            val current = session
            if (current == null) {
                LoginScreen(loading, error) { identifier, password -> scope.launch { loading = true; error = null; runCatching { client.login(identifier, password) }.onSuccess { session = it; sync = runCatching { client.sync(it) }.getOrNull() }.onFailure { error = it.message ?: "Connexion impossible." }; loading = false } }
            } else when (page) {
                "verify" -> VerificationHub(current, sync, hasNfc, isNfcEnabled, { page = "automatic" }, { page = "manual" }, { page = "nfc" }, onOpenNfcSettings, { page = "home" })
                "automatic", "manual" -> NativeVerificationScreen(current, page, verificationApi, { scope.launch { sync = runCatching { client.sync(current) }.getOrNull(); page = "home" } }, { page = "verify" })
                "nfc" -> NfcVerificationScreen(nfcStatus, nfcBusy, { credentials -> onStartNfc(current, verificationApi, credentials) }, { page = "verify" })
                "qr" -> KeloQrScreen(current, { sync = it; page = "home" }, { page = "home" })
                "account" -> AccountScreen(current, sync, { scope.launch { loading = true; runCatching { client.sync(current) }.onSuccess { sync = it }.onFailure { error = it.message }; loading = false } }, { client.clearSession(); session = null; sync = null; page = "home" }, { onOpenWebsite("https://kelo-id.eu/") })
                else -> HomeScreen(current, sync, loading, incomingUri, error, { scope.launch { loading = true; runCatching { client.sync(current) }.onSuccess { sync = it }.onFailure { error = it.message }; loading = false } }, { page = "verify" }, { page = "qr" })
            }
        }
    }
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, onLogin: (String, String) -> Unit) {
    var identifier by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        KeloBrandHeader("Connexion sécurisée AT Protocol")
        Text("Bienvenue sur Kelo ID", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = KeloInk)
        Text("Connectez-vous avec votre handle ou DID. Le PDS est détecté automatiquement.", color = KeloMuted)
        KeloCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(identifier, { identifier = it }, Modifier.fillMaxWidth(), label = { Text("Handle ou DID") }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mot de passe / mot de passe d'application") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onLogin(identifier, password) }, enabled = !loading && identifier.isNotBlank() && password.isNotBlank(), Modifier.fillMaxWidth()) { if (loading) CircularProgressIndicator() else Text("Se connecter") }
                Text("Votre mot de passe est envoyé au PDS détecté et n'est pas stocké par Kelo ID.", color = KeloMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        KeloGradientCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Kelo ID", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall); Text("Une identité vérifiée et synchronisée avec Kelo Social.", color = Color.White.copy(alpha = .92f)) } }
    }
}

@Composable
private fun HomeScreen(session: AtSession, sync: VerificationSync?, loading: Boolean, incomingUri: Uri?, error: String?, onRefresh: () -> Unit, onVerify: () -> Unit, onQr: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        KeloBrandHeader("@${session.handle}")
        KeloGradientCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(if (sync?.verified == true) "Identité vérifiée" else "Vérification nécessaire", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black); Text(if (sync?.verified == true) "Votre statut est reconnu par Kelo ID et Kelo Social." else "Commencez une vérification pour activer votre identité Kelo.", color = Color.White.copy(alpha = .92f)) } }
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Compte", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(session.did, color = KeloMuted, style = MaterialTheme.typography.bodySmall); Text("PDS : ${session.pdsUrl}", color = KeloMuted, style = MaterialTheme.typography.bodySmall); Text("Statut : ${sync?.status ?: "synchronisation…"}") } }
        Button(onClick = onVerify, Modifier.fillMaxWidth()) { Text("Vérifier mon identité") }
        OutlinedButton(onClick = onQr, Modifier.fillMaxWidth()) { Text("Code ou QR code") }
        OutlinedButton(onClick = onRefresh, enabled = !loading, Modifier.fillMaxWidth()) { Text(if (loading) "Synchronisation…" else "Synchroniser avec Kelo ID") }
        incomingUri?.let { Text("Lien reçu : $it", color = KeloMuted, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun VerificationHub(session: AtSession, sync: VerificationSync?, hasNfc: Boolean, nfcEnabled: Boolean, automatic: () -> Unit, manual: () -> Unit, nfc: () -> Unit, openNfc: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KeloBrandHeader("Vérification d'identité")
        Text("Choisissez votre méthode", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = KeloInk)
        Text("La décision finale est enregistrée côté Kelo ID/Supabase et synchronisée avec votre DID.", color = KeloMuted)
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Automatique", fontWeight = FontWeight.Bold); Text("Document + contrôle vidéo/liveness.", color = KeloMuted); Button(onClick = automatic, Modifier.fillMaxWidth()) { Text("Commencer") } } }
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Manuelle", fontWeight = FontWeight.Bold); Text("Photo du document + vidéo, puis validation Kelo ID.", color = KeloMuted); Button(onClick = manual, Modifier.fillMaxWidth()) { Text("Commencer") } } }
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Carte d'identité NFC", fontWeight = FontWeight.Bold); Text(if (!hasNfc) "NFC indisponible sur cet appareil." else if (!nfcEnabled) "NFC désactivé." else "Lecture sécurisée de la puce.", color = KeloMuted); Button(onClick = { if (nfcEnabled) nfc() else openNfc() }, enabled = hasNfc, Modifier.fillMaxWidth()) { Text("Utiliser NFC") } } }
        sync?.let { Text("Statut actuel : ${it.status}", color = KeloMuted) }
        TextButton(onClick = back) { Text("Retour") }
    }
}

@Composable
private fun AccountScreen(session: AtSession, sync: VerificationSync?, refresh: () -> Unit, logout: () -> Unit, website: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KeloBrandHeader("Mon compte")
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Identité AT Protocol", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("@${session.handle}"); Text(session.did, color = KeloMuted, style = MaterialTheme.typography.bodySmall); Text("PDS détecté automatiquement : ${session.pdsUrl}", color = KeloMuted, style = MaterialTheme.typography.bodySmall) } }
        KeloCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Kelo ID", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Statut : ${sync?.status ?: "inconnu"}"); Text("Type : ${sync?.verificationType ?: "human"}"); sync?.verifiedAt?.let { Text("Vérifié le : $it", color = KeloMuted) } } }
        OutlinedButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("Synchroniser") }
        OutlinedButton(onClick = website, Modifier.fillMaxWidth()) { Text("Ouvrir Kelo ID sur le web") }
        Button(onClick = logout, Modifier.fillMaxWidth()) { Text("Déconnexion") }
    }
}

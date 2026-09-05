package eu.keloid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Écran principal natif de Kelo ID.
 *
 * Cette activité ne charge plus le site dans une WebView : l'interface Android
 * est native. Les parcours photo, vidéo, QR et NFC seront branchés sur les
 * boutons de cet écran au fur et à mesure de leur implémentation.
 */
class MainActivity : ComponentActivity() {

    private val nfcAdapter: NfcAdapter? by lazy {
        NfcAdapter.getDefaultAdapter(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUri = intent?.data

        setContent {
            MaterialTheme {
                KeloIdApp(
                    incomingUri = incomingUri,
                    hasNfc = nfcAdapter != null,
                    isNfcEnabled = nfcAdapter?.isEnabled == true,
                    onOpenNfcSettings = ::openNfcSettings,
                    onOpenKeloIdWebsite = {
                        openWebsite("https://kelo-id.eu/")
                    },
                    onOpenKeloSocial = {
                        openWebsite("https://kelosocial.eu/")
                    }
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
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_NFC
        } else {
            Settings.ACTION_NFC_SETTINGS
        }
        runCatching {
            startActivity(Intent(action))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun openWebsite(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun KeloIdApp(
    incomingUri: Uri?,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit,
    onOpenKeloIdWebsite: () -> Unit,
    onOpenKeloSocial: () -> Unit
) {
    var cameraGranted by remember {
        mutableStateOf(false)
    }
    var microphoneGranted by remember {
        mutableStateOf(false)
    }
    var notificationsGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
    }

    val microphoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneGranted = granted
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        cameraGranted =
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        microphoneGranted =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold { padding ->
        KeloIdHome(
            padding = padding,
            incomingUri = incomingUri,
            cameraGranted = cameraGranted,
            microphoneGranted = microphoneGranted,
            notificationsGranted = notificationsGranted,
            hasNfc = hasNfc,
            isNfcEnabled = isNfcEnabled,
            onRequestCamera = {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            },
            onRequestMicrophone = {
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenNfcSettings = onOpenNfcSettings,
            onOpenKeloIdWebsite = onOpenKeloIdWebsite,
            onOpenKeloSocial = onOpenKeloSocial
        )
    }
}

@Composable
private fun KeloIdHome(
    padding: PaddingValues,
    incomingUri: Uri?,
    cameraGranted: Boolean,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    hasNfc: Boolean,
    isNfcEnabled: Boolean,
    onRequestCamera: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onOpenKeloIdWebsite: () -> Unit,
    onOpenKeloSocial: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Kelo ID",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Vérifiez votre identité et votre statut humain pour utiliser les fonctions protégées de Kelo Social.",
            style = MaterialTheme.typography.bodyLarge
        )

        if (incomingUri != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ouvert depuis Kelo Social ou Kelo ID",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = incomingUri.toString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text(
            text = "Choisir une méthode",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        VerificationButton(
            title = "Vérification automatique",
            description = "Photo du document et vérification vidéo.",
            onClick = {
                if (!cameraGranted) onRequestCamera()
                else if (!microphoneGranted) onRequestMicrophone()
            }
        )

        VerificationButton(
            title = "Vérification manuelle",
            description = "Envoyer les éléments nécessaires pour une validation humaine.",
            onClick = {
                if (!cameraGranted) onRequestCamera()
            }
        )

        VerificationButton(
            title = "Scanner un QR code",
            description = "Utiliser la caméra pour relier une vérification commencée sur Kelo Social ou Kelo ID.",
            onClick = {
                if (!cameraGranted) onRequestCamera()
            }
        )

        VerificationButton(
            title = "Carte d'identité NFC",
            description = when {
                !hasNfc -> "Ce téléphone ne possède pas de lecteur NFC compatible."
                !isNfcEnabled -> "Le NFC est disponible mais désactivé."
                else -> "Le NFC est prêt pour la lecture sécurisée de votre carte."
            },
            enabled = hasNfc,
            onClick = {
                if (!isNfcEnabled) onOpenNfcSettings()
            }
        )

        Text(
            text = "Autorisations",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        PermissionCard(
            title = "Caméra",
            description = "Nécessaire pour les QR codes, les photos de documents et la vérification vidéo.",
            granted = cameraGranted,
            onRequest = onRequestCamera
        )

        PermissionCard(
            title = "Microphone",
            description = "Utilisé uniquement lorsque le parcours de vérification vidéo en a besoin.",
            granted = microphoneGranted,
            onRequest = onRequestMicrophone
        )

        PermissionCard(
            title = "Notifications",
            description = "Permet de vous prévenir lorsqu'une vérification est acceptée, refusée ou nécessite une action.",
            granted = notificationsGranted,
            onRequest = onRequestNotifications
        )

        Text(
            text = "Liens Kelo",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenKeloSocial
        ) {
            Text("Ouvrir Kelo Social")
        }

        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenKeloIdWebsite
        ) {
            Text("Ouvrir le site Kelo ID")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun VerificationButton(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onClick
            ) {
                Text("Continuer")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (granted) "Autorisé" else "Autorisation requise",
                fontWeight = FontWeight.Medium
            )
            if (!granted) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequest
                ) {
                    Text("Autoriser")
                }
            }
        }
    }
}

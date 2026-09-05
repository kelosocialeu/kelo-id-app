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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.keloid.nfc.IdentityAccessCredentials

@Composable
internal fun NfcVerificationScreen(
    status: String?,
    busy: Boolean,
    onStart: (IdentityAccessCredentials) -> Unit,
    onBack: () -> Unit
) {
    var country by remember { mutableStateOf("BE") }
    var documentNumber by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var expiryDate by remember { mutableStateOf("") }
    var can by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        KeloBrandHeader(subtitle = "Lecture sécurisée de votre carte")
        Text(
            "Vérification NFC",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = KeloInk
        )
        Text(
            "Approchez votre carte d'identité du téléphone. Les données de la puce sont contrôlées avant l'envoi d'une preuve à Kelo ID.",
            color = KeloMuted
        )

        KeloCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Informations du document", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pays (BE, FR, ...)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = documentNumber,
                    onValueChange = { documentNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numéro du document") },
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
                    value = expiryDate,
                    onValueChange = { expiryDate = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Date d'expiration (AAAA-MM-JJ)") },
                    singleLine = true
                )
                if (country.uppercase() == "FR") {
                    OutlinedTextField(
                        value = can,
                        onValueChange = { can = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("CAN") },
                        singleLine = true
                    )
                }
            }
        }

        KeloGradientCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("État NFC", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                Text(
                    status ?: "Renseignez les champs puis placez la carte contre le téléphone.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f)
                )
            }
        }

        Button(
            onClick = {
                onStart(
                    IdentityAccessCredentials(
                        issuerCountry = country.trim().uppercase(),
                        documentNumber = documentNumber.trim(),
                        birthDate = birthDate.trim(),
                        expiryDate = expiryDate.trim(),
                        can = can.trim().takeIf { it.isNotBlank() }
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && country.isNotBlank() && documentNumber.isNotBlank() && birthDate.length == 10 && expiryDate.length == 10
        ) {
            Text(if (busy) "Lecture en cours…" else "Démarrer la lecture NFC")
        }
        TextButton(onClick = onBack) { Text("Retour") }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

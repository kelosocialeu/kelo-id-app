package eu.keloid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lecture NFC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(Modifier.fillMaxWidth(), country, { country = it.uppercase() }, label = { Text("Pays (BE, FR, ...)") }, singleLine = true)
        OutlinedTextField(Modifier.fillMaxWidth(), documentNumber, { documentNumber = it }, label = { Text("Numéro du document") }, singleLine = true)
        OutlinedTextField(Modifier.fillMaxWidth(), birthDate, { birthDate = it }, label = { Text("Date de naissance (AAAA-MM-JJ)") }, singleLine = true)
        OutlinedTextField(Modifier.fillMaxWidth(), expiryDate, { expiryDate = it }, label = { Text("Date d'expiration (AAAA-MM-JJ)") }, singleLine = true)
        if (country.uppercase() == "FR") {
            OutlinedTextField(Modifier.fillMaxWidth(), can, { can = it }, label = { Text("CAN") }, singleLine = true)
        }
        Text(status ?: "Renseignez les champs puis placez la carte contre le téléphone.")
        Button(
            Modifier.fillMaxWidth(),
            enabled = !busy && country.isNotBlank() && documentNumber.isNotBlank() && birthDate.length == 10 && expiryDate.length == 10,
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
            }
        ) { Text(if (busy) "Lecture en cours…" else "Démarrer") }
        TextButton(onClick = onBack) { Text("Retour") }
    }
}

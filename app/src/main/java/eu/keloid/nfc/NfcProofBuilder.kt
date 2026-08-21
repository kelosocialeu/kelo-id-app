package eu.keloid.nfc

import org.json.JSONObject
import java.time.Instant

class NfcProofBuilder(
    private val signingKeyManager: NfcSigningKeyManager
) {
    fun build(subjectDid: String, identity: AuthenticatedIdentity): NativeProof {
        require(identity.documentAuthentic) {
            "La puce a été lue, mais la signature/intégrité ICAO du document n’a pas pu être confirmée."
        }
        require(identity.chipAuthenticated) {
            "Le canal sécurisé avec la puce n'est pas authentifié."
        }
        require(identity.issuerTrusted) {
            identity.trustDiagnostic ?: "La chaîne de confiance de l’autorité émettrice n’a pas pu être validée."
        }

        val payload = JSONObject()
            .put("version", 1)
            .put("subjectDid", subjectDid)
            .put("firstName", identity.firstName)
            .put("lastName", identity.lastName)
            .put("birthDate", identity.birthDate)
            .put("issuerCountry", identity.issuerCountry)
            .put("documentAuthentic", identity.documentAuthentic)
            .put("chipAuthenticated", identity.chipAuthenticated)
            .put("issuerTrusted", identity.issuerTrusted)
            .put("trustDiagnostic", identity.trustDiagnostic ?: JSONObject.NULL)
            .put("accessControl", identity.accessControl.name.lowercase())
            .put("readAt", Instant.now().toString())
            .toString()

        val signed = signingKeyManager.sign(payload.toByteArray(Charsets.UTF_8))
        return NativeProof(
            payload = payload,
            signature = signed.signatureBase64,
            algorithm = signed.algorithm,
            keyId = signed.keyId
        )
    }
}

data class NativeProof(
    val payload: String,
    val signature: String,
    val algorithm: String,
    val keyId: String
)

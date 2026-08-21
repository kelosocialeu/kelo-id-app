package eu.keloid.nfc

import android.nfc.Tag

/**
 * Données nécessaires pour établir un canal ICAO BAC/PACE.
 * Elles proviennent de la face imprimée/MRZ du document et ne sont pas
 * persistées par le module Android.
 */
data class IdentityAccessCredentials(
    val issuerCountry: String,
    val documentNumber: String,
    val birthDate: String,
    val expiryDate: String,
    val can: String? = null,
)

interface IdentityCardReader {
    fun supports(tag: Tag, credentials: IdentityAccessCredentials): Boolean

    suspend fun read(
        tag: Tag,
        credentials: IdentityAccessCredentials,
    ): AuthenticatedIdentity
}

enum class AccessControl {
    PIN,
    CAN,
    MRZ,
    PACE,
    BAC,
    NONE
}

data class AuthenticatedIdentity(
    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val issuerCountry: String,
    val documentAuthentic: Boolean,
    val chipAuthenticated: Boolean,
    val issuerTrusted: Boolean,
    val trustDiagnostic: String? = null,
    val accessControl: AccessControl
)

class IdentityCardReaderRegistry(
    private val readers: List<IdentityCardReader>
) {
    suspend fun read(
        tag: Tag,
        credentials: IdentityAccessCredentials,
    ): AuthenticatedIdentity {
        val reader = readers.firstOrNull { it.supports(tag, credentials) }
            ?: throw UnsupportedOperationException(
                "Ce document NFC n’expose pas un profil ICAO/eMRTD compatible avec Kelo ID."
            )
        return reader.read(tag, credentials)
    }
}

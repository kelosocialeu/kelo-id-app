package eu.keloid.nfc

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class NfcSigningKeyManager {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kelo_id_nfc_proof_p256_v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun ensureKey(): DeviceKeyInfo {
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val certificate = keyStore.getCertificate(ALIAS)
            ?: error("Clé NFC Android Keystore introuvable")
        val encoded = certificate.publicKey.encoded
        val keyId = sha256Hex(encoded)
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.encodeToString(encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            append("\n-----END PUBLIC KEY-----")
        }
        return DeviceKeyInfo(keyId, pem)
    }

    fun sign(payload: ByteArray): SignedPayload {
        val info = ensureKey()
        val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
            ?: error("Clé privée NFC Android Keystore introuvable")

        val signer = createAndroidKeystoreSigner(privateKey)
        signer.update(payload)
        val signature = signer.sign()

        return SignedPayload(
            keyId = info.keyId,
            signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        )
    }

    private fun createAndroidKeystoreSigner(privateKey: PrivateKey): Signature {
        val candidates = Security.getProviders("Signature.$SIGNATURE_ALGORITHM")
            ?.filterNot { it.name.equals("SC", ignoreCase = true) }
            .orEmpty()

        var lastError: Throwable? = null
        for (provider in candidates) {
            try {
                val signer = Signature.getInstance(SIGNATURE_ALGORITHM, provider)
                signer.initSign(privateKey)
                return signer
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IllegalStateException(
            "Aucun provider Android compatible avec la clé de signature NFC sécurisée n’est disponible.",
            lastError,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

data class DeviceKeyInfo(
    val keyId: String,
    val publicKeyPem: String,
    val algorithm: String = "ES256"
)

data class SignedPayload(
    val keyId: String,
    val signatureBase64: String,
    val algorithm: String = "ES256"
)

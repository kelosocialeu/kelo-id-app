package eu.keloid.nfc

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.SODFile
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.time.LocalDate

/**
 * Lecteur ICAO/eMRTD partagé.
 *
 * - Belgique : conserve le parcours MRZ -> PACE/BAC déjà utilisé par Kelo ID.
 * - France CNIe : ajoute un parcours CAN -> PACE. La lecture peut être tentée,
 *   mais aucune preuve Kelo ID n'est produite tant que la chaîne CSCA française
 *   n'est pas configurée et validée, car NfcProofBuilder exige issuerTrusted=true.
 */
class IcaoMrtdReader(context: Context) : IdentityCardReader {
    private val belgianTrustStore = BelgianCscaTrustStore(context.applicationContext)

    companion object {
        init {
            Security.removeProvider("SC")
            Security.insertProviderAt(org.spongycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }

    override fun supports(tag: Tag, credentials: IdentityAccessCredentials): Boolean {
        if (IsoDep.get(tag) == null) return false

        val country = credentials.issuerCountry.uppercase()
        if (country == "FR" && !credentials.can.isNullOrBlank()) {
            return true
        }

        return credentials.documentNumber.isNotBlank() &&
            credentials.birthDate.isNotBlank() &&
            credentials.expiryDate.isNotBlank()
    }

    override suspend fun read(tag: Tag, credentials: IdentityAccessCredentials): AuthenticatedIdentity =
        withContext(Dispatchers.IO) {
            val isoDep = IsoDep.get(tag)
                ?: throw IllegalArgumentException("Ce document ne prend pas en charge ISO-DEP.")

            isoDep.timeout = maxOf(isoDep.timeout, 10_000)
            val cardService = CardService.getInstance(isoDep)
            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                true,
            )

            val country = credentials.issuerCountry.uppercase()
            val useCanForPace = country == "FR" && !credentials.can.isNullOrBlank()

            try {
                service.open()

                val bacKey = if (!useCanForPace) {
                    BACKey(
                        cleanDocumentNumber(credentials.documentNumber),
                        toMrzDate(credentials.birthDate),
                        toMrzDate(credentials.expiryDate),
                    )
                } else {
                    null
                }

                var paceSucceeded = false
                var paceCryptoFailure: Throwable? = null
                var paceFailure: Throwable? = null

                try {
                    val cardAccess = CardAccessFile(
                        service.getInputStream(
                            PassportService.EF_CARD_ACCESS,
                            PassportService.DEFAULT_MAX_BLOCKSIZE,
                        )
                    )

                    val paceKey = if (useCanForPace) {
                        PACEKeySpec.createCANKey(credentials.can!!.trim())
                    } else {
                        PACEKeySpec.createMRZKey(bacKey!!)
                    }

                    for (securityInfo in cardAccess.securityInfos) {
                        if (securityInfo is PACEInfo) {
                            try {
                                service.doPACE(
                                    paceKey,
                                    securityInfo.objectIdentifier,
                                    PACEInfo.toParameterSpec(securityInfo.parameterId),
                                    securityInfo.parameterId,
                                )
                                paceSucceeded = true
                                break
                            } catch (error: Exception) {
                                paceFailure = error
                                if (isEcProviderFailure(error)) paceCryptoFailure = error
                            }
                        }
                    }
                } catch (error: Exception) {
                    paceFailure = error
                    if (isEcProviderFailure(error)) paceCryptoFailure = error
                }

                service.sendSelectApplet(paceSucceeded)

                if (!paceSucceeded) {
                    if (useCanForPace) {
                        if (paceCryptoFailure != null) {
                            throw IllegalStateException(
                                "PACE/CAN est annoncé par cette CNIe mais Android n’a pas pu initialiser correctement la cryptographie EC.",
                                paceCryptoFailure,
                            )
                        }
                        throw IllegalStateException(
                            "La CNIe française a refusé l’ouverture PACE avec le CAN fourni.",
                            paceFailure,
                        )
                    }

                    try {
                        service.doBAC(bacKey!!)
                    } catch (bacError: Exception) {
                        if (paceCryptoFailure != null) {
                            throw IllegalStateException(
                                "PACE est annoncé par ce document mais Android n’a pas pu initialiser correctement la cryptographie EC.",
                                paceCryptoFailure,
                            )
                        }
                        throw bacError
                    }
                }

                val dg1 = DG1File(
                    service.getInputStream(
                        PassportService.EF_DG1,
                        PassportService.DEFAULT_MAX_BLOCKSIZE,
                    )
                )
                val mrz = dg1.mrzInfo

                val sod = SODFile(
                    service.getInputStream(
                        PassportService.EF_SOD,
                        PassportService.DEFAULT_MAX_BLOCKSIZE,
                    )
                )

                val expectedDg1Hash: ByteArray? = sod.dataGroupHashes[1]
                val calculatedDg1Hash: ByteArray = MessageDigest
                    .getInstance(sod.digestAlgorithm)
                    .digest(dg1.encoded)
                val dg1IntegrityOk = expectedDg1Hash?.contentEquals(calculatedDg1Hash) == true

                val dsCertificate: X509Certificate? = sod.docSigningCertificate
                val sodSignatureOk = verifySodSignature(sod, dsCertificate)
                val normalizedIssuerCountry = normalizeCountry(mrz.issuingState, country)

                val trustResult = when (normalizedIssuerCountry) {
                    "BE" -> belgianTrustStore.verifyDocumentSigner(
                        dsCertificate,
                        sod.docSigningCertificates,
                    )
                    "FR" -> BelgianTrustResult(
                        false,
                        "Lecture CNIe française effectuée, mais la chaîne CSCA française n’est pas encore configurée dans Kelo ID. Aucune validation définitive n’est autorisée.",
                    )
                    else -> BelgianTrustResult(
                        false,
                        "Aucun trust store configuré pour $normalizedIssuerCountry.",
                    )
                }

                AuthenticatedIdentity(
                    firstName = mrz.secondaryIdentifier.orEmpty().replace('<', ' ').trim(),
                    lastName = mrz.primaryIdentifier.orEmpty().replace('<', ' ').trim(),
                    birthDate = expandMrzBirthDate(mrz.dateOfBirth),
                    issuerCountry = normalizedIssuerCountry,
                    documentAuthentic = dg1IntegrityOk && sodSignatureOk,
                    chipAuthenticated = paceSucceeded || !useCanForPace,
                    issuerTrusted = trustResult.trusted,
                    trustDiagnostic = trustResult.diagnostic,
                    accessControl = when {
                        useCanForPace && paceSucceeded -> AccessControl.CAN
                        paceSucceeded -> AccessControl.PACE
                        else -> AccessControl.BAC
                    },
                )
            } catch (error: Throwable) {
                throw IllegalStateException(friendlyReadError(error), error)
            } finally {
                try { service.close() } catch (_: Exception) {}
                try { isoDep.close() } catch (_: Exception) {}
            }
        }

    private fun friendlyReadError(error: Throwable): String {
        val chain = causeChain(error)
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }

        return when {
            isEcProviderFailure(error) ->
                "Erreur cryptographique PACE/EC sur cet appareil. Kelo ID n’a pas pu créer la clé EC temporaire nécessaire à la session NFC."

            chain.any { it.javaClass.simpleName.contains("TagLost", ignoreCase = true) } ||
                text.contains("tag was lost", ignoreCase = true) ->
                "La carte a été éloignée trop tôt. Gardez-la parfaitement immobile contre la zone NFC jusqu’à la fin de la lecture."

            text.contains("failed to connect", ignoreCase = true) ||
                text.contains("transceive", ignoreCase = true) ||
                text.contains("connection", ignoreCase = true) ->
                "La connexion NFC avec la carte a été interrompue. Replacez la carte contre le téléphone et réessayez sans la bouger."

            text.contains("CAN", ignoreCase = true) ->
                "La puce a refusé l’ouverture PACE/CAN. Vérifiez le CAN imprimé sur la carte et gardez la CNIe immobile contre le téléphone."

            text.contains("BAC failed", ignoreCase = true) ||
                text.contains("BAC authentication", ignoreCase = true) ||
                text.contains("SW = 6300", ignoreCase = true) ||
                text.contains("SW = 6982", ignoreCase = true) ->
                "La puce a refusé la clé BAC/MRZ. Vérifiez le numéro du document, la date de naissance et la date d’expiration tels qu’imprimés sur la carte."

            text.contains("PACE", ignoreCase = true) ->
                "L’ouverture PACE de la puce a échoué. Gardez la carte immobile et réessayez."

            else ->
                "Lecture NFC impossible. Diagnostic technique : ${compactDiagnostic(text)}"
        }
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            result += current
            current = current.cause
            depth++
        }
        return result
    }

    private fun compactDiagnostic(value: String): String =
        value.replace(Regex("\\s+"), " ").take(280)

    private fun isEcProviderFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("cannot identify EC private key", ignoreCase = true) ||
                message.contains("no encoding for EC private key", ignoreCase = true)
            ) return true
            current = current.cause
        }
        return false
    }

    private fun verifySodSignature(sod: SODFile, certificate: Certificate?): Boolean {
        if (certificate == null) return false
        return try {
            val rawAlgorithm = sod.digestEncryptionAlgorithm ?: return false
            val algorithm = when (rawAlgorithm.uppercase()) {
                "RSA" -> sod.signerInfoDigestAlgorithm.replace("-", "") + "withRSA"
                "SSAWITHRSA/PSS" -> sod.signerInfoDigestAlgorithm.replace("-", "") + "withRSA/PSS"
                else -> rawAlgorithm
            }
            val verifier = try {
                Signature.getInstance(algorithm, "SC")
            } catch (_: Exception) {
                Signature.getInstance(algorithm)
            }
            verifier.initVerify(certificate.publicKey)
            verifier.update(sod.eContent)
            verifier.verify(sod.encryptedDigest)
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanDocumentNumber(value: String): String =
        value.uppercase().replace(" ", "").replace("-", "")

    private fun toMrzDate(value: String): String {
        val date = LocalDate.parse(value)
        return "%02d%02d%02d".format(date.year % 100, date.monthValue, date.dayOfMonth)
    }

    private fun expandMrzBirthDate(value: String): String {
        if (value.length != 6) return value
        val yy = value.substring(0, 2).toIntOrNull() ?: return value
        val mm = value.substring(2, 4)
        val dd = value.substring(4, 6)
        val currentTwoDigits = LocalDate.now().year % 100
        val century = if (yy > currentTwoDigits) 1900 else 2000
        return "%04d-%s-%s".format(century + yy, mm, dd)
    }

    private fun normalizeCountry(mrzState: String?, fallback: String): String {
        val state = mrzState?.uppercase().orEmpty()
        return when (state) {
            "BEL" -> "BE"
            "FRA" -> "FR"
            "DEU", "D<<" -> "DE"
            "ESP" -> "ES"
            "ITA" -> "IT"
            "NLD" -> "NL"
            "PRT" -> "PT"
            else -> fallback.uppercase()
        }
    }
}

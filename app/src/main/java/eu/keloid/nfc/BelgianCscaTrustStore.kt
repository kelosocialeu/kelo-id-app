package eu.keloid.nfc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.spongycastle.asn1.ASN1OctetString
import org.spongycastle.asn1.ASN1Primitive
import org.spongycastle.asn1.DERIA5String
import org.spongycastle.asn1.x509.AccessDescription
import org.spongycastle.asn1.x509.AuthorityInformationAccess
import org.spongycastle.asn1.x509.Extension
import org.spongycastle.asn1.x509.GeneralName
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class BelgianTrustResult(
    val trusted: Boolean,
    val diagnostic: String,
)

class BelgianCscaTrustStore(private val context: Context) {
    companion object {
        private const val EID_INDEX = "https://certs.eid.belgium.be/"

        private val STATIC_FALLBACK_URLS = listOf(
            "https://certs.eid.belgium.be/csca-05.crt",
            "https://certs.eid.belgium.be/csca-05-link.crt",
            "https://certs.eid.belgium.be/csca-04.crt",
            "https://certs.eid.belgium.be/csca-04-link.crt",
            "https://certs.eid.belgium.be/csca-03.crt",
            "https://certs.eid.belgium.be/csca-03-link.crt",
            "https://certs.eid.belgium.be/csca-02.crt",
            "https://certs.eid.belgium.be/csca-02-link.crt",
            "https://certs.eid.belgium.be/csca-01.crt",
        )

        private val TRUSTED_HTTPS_HOSTS = setOf(
            "certs.eid.belgium.be",
            "csca-pass.belgium.be",
            "crt.eidpki.belgium.be",
            "repository.eid.belgium.be",
        )

        private val CSCA_LINK_REGEX = Regex(
            "href=[\\\"']([^\\\"']*csca-[0-9]+(?:-link)?\\.crt)[\\\"']",
            RegexOption.IGNORE_CASE,
        )

        private const val MAX_AIA_DEPTH = 4
    }

    suspend fun verifyDocumentSigner(
        documentSigner: X509Certificate?,
        embeddedCertificates: Collection<X509Certificate> = emptyList(),
    ): BelgianTrustResult = withContext(Dispatchers.IO) {
        if (documentSigner == null) {
            return@withContext BelgianTrustResult(false, "Aucun certificat DSC présent dans le SOD.")
        }

        val candidates = LinkedHashSet<X509Certificate>()
        candidates += embeddedCertificates.filter { looksLikeBelgianCa(it) }
        candidates += loadBundledCertificates()
        candidates += downloadOfficialCertificates()

        val aiaChain = resolveAiaChain(documentSigner)
        candidates += aiaChain.map { it.certificate }.filter { looksLikeBelgianCa(it) }

        for (candidate in candidates) {
            if (!verifies(documentSigner, candidate)) continue
            if (isTrustedAnchor(candidate, aiaChain)) {
                return@withContext BelgianTrustResult(
                    true,
                    "DSC validé par ${candidate.subjectX500Principal.name} (source gouvernementale belge).",
                )
            }
        }

        val chain = buildTrustedChain(documentSigner, candidates.toList(), aiaChain)
        if (chain != null) {
            return@withContext BelgianTrustResult(
                true,
                "Chaîne belge validée : ${chain.joinToString(" → ") { shortName(it) }}",
            )
        }

        val aiaDiagnostic = if (aiaChain.isEmpty()) {
            "Aucune URL AIA caIssuers exploitable dans le DSC."
        } else {
            "AIA: " + aiaChain.joinToString(" | ") {
                "${shortName(it.certificate)} @ ${it.sourceUrl}"
            }
        }

        val subjects = candidates
            .map { it.subjectX500Principal.name }
            .distinct()
            .take(10)
            .joinToString(" | ")

        BelgianTrustResult(
            false,
            "DSC issuer: ${documentSigner.issuerX500Principal.name}; ${candidates.size} certificat(s) belge(s) testé(s), aucune chaîne de confiance valide. $aiaDiagnostic Candidats: $subjects",
        )
    }

    private data class SourcedCertificate(
        val certificate: X509Certificate,
        val sourceUrl: String,
        val trustedHttpsSource: Boolean,
    )

    private fun resolveAiaChain(start: X509Certificate): List<SourcedCertificate> {
        val result = mutableListOf<SourcedCertificate>()
        val seenFingerprints = mutableSetOf<String>()
        var current = start

        repeat(MAX_AIA_DEPTH) {
            val urls = extractCaIssuersUrls(current)
            if (urls.isEmpty()) return result

            var next: SourcedCertificate? = null
            for (source in urls) {
                val downloaded = downloadCertificateFromAia(source) ?: continue
                val fp = fingerprint(downloaded.certificate)
                if (!seenFingerprints.add(fp)) continue
                if (!verifies(current, downloaded.certificate)) continue
                result += downloaded
                next = downloaded
                break
            }

            val parent = next ?: return result
            if (isSelfSigned(parent.certificate)) return result
            current = parent.certificate
        }
        return result
    }

    private fun extractCaIssuersUrls(cert: X509Certificate): List<String> {
        return try {
            val extensionBytes = cert.getExtensionValue(Extension.authorityInfoAccess.id) ?: return emptyList()
            val outerOctets = ASN1OctetString.getInstance(extensionBytes).octets
            val aia = AuthorityInformationAccess.getInstance(ASN1Primitive.fromByteArray(outerOctets))

            aia.accessDescriptions.mapNotNull { access ->
                if (access.accessMethod != AccessDescription.id_ad_caIssuers) return@mapNotNull null
                val location = access.accessLocation
                if (location.tagNo != GeneralName.uniformResourceIdentifier) return@mapNotNull null
                DERIA5String.getInstance(location.name).string?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun downloadCertificateFromAia(source: String): SourcedCertificate? {
        return try {
            val url = URL(source)
            if (url.protocol !in setOf("https", "http")) return null
            if (!isBelgianGovernmentHost(url.host)) return null

            val cert = downloadCertificateRaw(url) ?: return null
            SourcedCertificate(
                certificate = cert,
                sourceUrl = source,
                trustedHttpsSource = url.protocol == "https" && url.host.lowercase() in TRUSTED_HTTPS_HOSTS,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildTrustedChain(
        leaf: X509Certificate,
        candidates: List<X509Certificate>,
        aiaChain: List<SourcedCertificate>,
    ): List<X509Certificate>? {
        fun recurse(current: X509Certificate, path: List<X509Certificate>, depth: Int): List<X509Certificate>? {
            if (depth > MAX_AIA_DEPTH) return null
            for (parent in candidates) {
                if (parent in path || !verifies(current, parent)) continue
                val newPath = path + parent
                if (isTrustedAnchor(parent, aiaChain)) return newPath
                recurse(parent, newPath, depth + 1)?.let { return it }
            }
            return null
        }
        return recurse(leaf, listOf(leaf), 0)
    }

    private fun isTrustedAnchor(
        cert: X509Certificate,
        aiaChain: List<SourcedCertificate>,
    ): Boolean {
        if (!isSelfSigned(cert) || !looksLikeBelgianCa(cert)) return false
        if (isBundledCertificate(cert)) return true

        return aiaChain.any {
            fingerprint(it.certificate) == fingerprint(cert) && it.trustedHttpsSource
        } || downloadedFromOfficialIndex(cert)
    }

    private fun isBundledCertificate(cert: X509Certificate): Boolean =
        loadBundledCertificates().any { fingerprint(it) == fingerprint(cert) }

    private fun downloadedFromOfficialIndex(cert: X509Certificate): Boolean =
        downloadOfficialCertificates().any {
            fingerprint(it) == fingerprint(cert) && isSelfSigned(it)
        }

    private fun verifies(child: X509Certificate, parent: X509Certificate): Boolean {
        val issuerMatches = child.issuerX500Principal == parent.subjectX500Principal ||
            normalizeDn(child.issuerX500Principal.name) == normalizeDn(parent.subjectX500Principal.name)
        if (!issuerMatches) return false

        return try {
            child.verify(parent.publicKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isSelfSigned(cert: X509Certificate): Boolean {
        val sameName = normalizeDn(cert.subjectX500Principal.name) == normalizeDn(cert.issuerX500Principal.name)
        if (!sameName) return false
        return try {
            cert.verify(cert.publicKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeDn(value: String): Set<String> =
        value.split(',')
            .map { it.trim().lowercase().replace(" ", "") }
            .filter { it.isNotBlank() }
            .toSet()

    private fun looksLikeBelgianCa(cert: X509Certificate): Boolean {
        val subject = cert.subjectX500Principal.name.uppercase()
        return subject.contains("C=BE") || subject.contains("C = BE")
    }

    private fun isBelgianGovernmentHost(host: String?): Boolean {
        val normalized = host?.lowercase()?.trimEnd('.') ?: return false
        return normalized in TRUSTED_HTTPS_HOSTS || normalized.endsWith(".belgium.be")
    }

    private fun loadBundledCertificates(): List<X509Certificate> {
        return try {
            val names = context.assets.list("csca")?.toList().orEmpty()
            names.mapNotNull { name ->
                try {
                    val bytes = context.assets.open("csca/$name").use { it.readBytes() }
                    parseCertificate(bytes)
                } catch (_: Exception) {
                    null
                }
            }.filter { looksLikeBelgianCa(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun downloadOfficialCertificates(): List<X509Certificate> {
        val urls = LinkedHashSet<String>()
        urls += STATIC_FALLBACK_URLS
        urls += discoverCertificateUrls(EID_INDEX)

        val result = LinkedHashSet<X509Certificate>()
        for (source in urls) {
            val url = runCatching { URL(source) }.getOrNull() ?: continue
            if (url.protocol != "https" || url.host.lowercase() !in TRUSTED_HTTPS_HOSTS) continue
            val cert = downloadCertificateRaw(url) ?: continue
            if (looksLikeBelgianCa(cert)) result += cert
        }
        return result.toList()
    }

    private fun discoverCertificateUrls(indexUrl: String): List<String> {
        val html = downloadText(indexUrl) ?: return emptyList()
        val base = URL(indexUrl)
        val discovered = LinkedHashSet<String>()

        for (match in CSCA_LINK_REGEX.findAll(html)) {
            val href = match.groupValues[1]
            try {
                val resolved = URL(base, href)
                if (resolved.protocol == "https" && resolved.host.lowercase() in TRUSTED_HTTPS_HOSTS) {
                    discovered += resolved.toString()
                }
            } catch (_: Exception) {
            }
        }
        return discovered.toList()
    }

    private fun downloadText(source: String): String? {
        return try {
            val url = URL(source)
            if (url.protocol != "https" || url.host.lowercase() !in TRUSTED_HTTPS_HOSTS) return null

            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "KeloID-Android/2.0")
                setRequestProperty("Accept", "text/html,*/*")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadCertificateRaw(url: URL): X509Certificate? {
        return try {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "KeloID-Android/2.0")
                setRequestProperty("Accept", "application/pkix-cert, application/x-x509-ca-cert, application/octet-stream, */*")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val finalUrl = connection.url
                if (!isBelgianGovernmentHost(finalUrl.host)) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.size !in 128..200_000) return null
                parseCertificate(bytes)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCertificate(bytes: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    } catch (_: Exception) {
        null
    }

    private fun fingerprint(cert: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun shortName(cert: X509Certificate): String =
        cert.subjectX500Principal.name
            .split(',')
            .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.trim()
            ?: cert.subjectX500Principal.name
}

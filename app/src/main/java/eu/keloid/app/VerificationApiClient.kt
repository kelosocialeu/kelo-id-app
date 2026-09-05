package eu.keloid.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal data class VerificationRequestStart(
    val requestId: String,
    val status: String,
    val method: String,
    val livenessChallenge: String?
)

internal data class SignedUpload(
    val path: String,
    val signedUrl: String
)

internal class VerificationApiClient(private val context: Context) {
    private val http = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://fbtloeehynqobbwcndru.supabase.co/functions/v1/kelo-id-native-verification"

    suspend fun startRequest(
        session: AtSession,
        method: String,
        firstName: String,
        lastName: String,
        birthDate: String,
        nationality: String
    ): VerificationRequestStart = withContext(Dispatchers.IO) {
        val data = call(
            session,
            JSONObject()
                .put("action", "startRequest")
                .put("method", method)
                .put("firstName", firstName.trim())
                .put("lastName", lastName.trim())
                .put("birthDate", birthDate.trim())
                .put("nationality", nationality.trim())
        )
        VerificationRequestStart(
            requestId = data.getString("requestId"),
            status = data.optString("status", "collecting"),
            method = data.optString("method", method),
            livenessChallenge = data.optString("livenessChallenge").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    suspend fun uploadEvidence(
        session: AtSession,
        requestId: String,
        kind: String,
        uri: Uri,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val signed = createUpload(session, requestId, kind, mimeType)
        val bytes = readBytes(uri)
        val uploadRequest = Request.Builder()
            .url(signed.signedUrl)
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .header("Content-Type", mimeType)
            .build()
        http.newCall(uploadRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("L'envoi du fichier a échoué (${response.code}).")
            }
        }
        call(
            session,
            JSONObject()
                .put("action", "confirmUpload")
                .put("requestId", requestId)
                .put("kind", kind)
                .put("path", signed.path)
                .put("mimeType", mimeType)
                .put("sizeBytes", bytes.size)
        )
    }

    suspend fun finalizeRequest(session: AtSession, requestId: String) = withContext(Dispatchers.IO) {
        call(session, JSONObject().put("action", "finalizeRequest").put("requestId", requestId))
    }

    suspend fun registerNfcDeviceKey(
        session: AtSession,
        keyId: String,
        publicKeyPem: String,
        algorithm: String
    ) = withContext(Dispatchers.IO) {
        call(
            session,
            JSONObject()
                .put("action", "registerNfcDeviceKey")
                .put("keyId", keyId)
                .put("publicKeyPem", publicKeyPem)
                .put("algorithm", algorithm)
        )
    }

    suspend fun submitNfcProof(
        session: AtSession,
        requestId: String,
        payload: String,
        signature: String,
        keyId: String,
        algorithm: String
    ) = withContext(Dispatchers.IO) {
        call(
            session,
            JSONObject()
                .put("action", "submitNfcProof")
                .put("requestId", requestId)
                .put("payload", payload)
                .put("signature", signature)
                .put("keyId", keyId)
                .put("algorithm", algorithm)
        )
    }

    private fun createUpload(
        session: AtSession,
        requestId: String,
        kind: String,
        mimeType: String
    ): SignedUpload {
        val data = callBlocking(
            session,
            JSONObject()
                .put("action", "createUpload")
                .put("requestId", requestId)
                .put("kind", kind)
                .put("mimeType", mimeType)
        )
        return SignedUpload(data.getString("path"), data.getString("signedUrl"))
    }

    private suspend fun call(session: AtSession, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        callBlocking(session, payload)
    }

    private fun callBlocking(session: AtSession, payload: JSONObject): JSONObject {
        payload.put("pdsUrl", session.pdsUrl)
        payload.put("accessJwt", session.accessJwt)
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
                val message = when (code) {
                    "ADULT_IDENTITY_REQUIRED" -> "La vérification Kelo ID est réservée aux personnes de 18 ans ou plus."
                    "IDENTITY_FIELDS_REQUIRED" -> "Nom, prénom, date de naissance et nationalité sont requis."
                    "EVIDENCE_INCOMPLETE" -> "Il manque la photo du document ou la vidéo de vérification."
                    "AT_SESSION_INVALID" -> "La session AT Protocol a expiré. Reconnectez-vous."
                    else -> "Kelo ID n'a pas pu traiter la demande ($code)."
                }
                throw IllegalStateException(message)
            }
            return JSONObject(raw)
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) output.write(buffer, 0, read)
                if (output.size() > 80 * 1024 * 1024) {
                    throw IllegalStateException("Le fichier dépasse la limite de 80 Mo.")
                }
            }
            return output.toByteArray()
        }
        throw IllegalStateException("Impossible de lire le fichier sélectionné.")
    }
}

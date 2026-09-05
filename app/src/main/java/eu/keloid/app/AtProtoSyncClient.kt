package eu.keloid.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class AtSession(
    val did: String,
    val handle: String,
    val pdsUrl: String,
    val accessJwt: String,
    val refreshJwt: String
)

internal data class VerificationSync(
    val verified: Boolean,
    val shouldVerify: Boolean,
    val status: String,
    val verificationType: String,
    val verifiedAt: String?,
    val linkCode: String? = null,
    val linkCodeExpiresAt: String? = null
)

internal class AtProtoSyncClient(context: Context) {
    private val http = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val syncUrl = "https://fbtloeehynqobbwcndru.supabase.co/functions/v1/kelo-id-atproto-sync"

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "kelo_id_at_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun login(identifier: String, password: String): AtSession = withContext(Dispatchers.IO) {
        val cleanIdentifier = identifier.trim().removePrefix("@")
        require(cleanIdentifier.isNotBlank()) { "Entrez votre identifiant AT Protocol." }

        val detected = detectPds(cleanIdentifier)
        val candidates = buildList {
            detected?.let { add(it) }
            addAll(fallbackCandidates(cleanIdentifier))
        }.map { it.removeSuffix("/") }.distinct()

        var lastError: String? = null
        for (base in candidates) {
            try {
                return@withContext createSession(base, cleanIdentifier, password)
            } catch (error: Exception) {
                lastError = error.message
            }
        }

        throw IllegalStateException(lastError ?: "Impossible de détecter automatiquement le PDS de ce compte.")
    }

    private fun createSession(base: String, identifier: String, password: String): AtSession {
        require(base.startsWith("https://")) { "Le PDS détecté n'utilise pas HTTPS." }
        val body = JSONObject()
            .put("identifier", identifier)
            .put("password", password)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$base/xrpc/com.atproto.server.createSession")
            .post(body)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Connexion refusée par le PDS détecté.")
            val data = JSONObject(raw)
            val session = AtSession(
                did = data.getString("did"),
                handle = data.getString("handle"),
                pdsUrl = base,
                accessJwt = data.getString("accessJwt"),
                refreshJwt = data.optString("refreshJwt")
            )
            saveSession(session)
            return session
        }
    }

    private fun detectPds(identifier: String): String? {
        if (identifier.startsWith("did:")) return resolveDidToPds(identifier)
        if (!identifier.contains("@") && identifier.contains(".")) {
            resolveHandleWellKnown(identifier)?.let { did -> resolveDidToPds(did)?.let { return it } }
            resolveHandleViaAppView(identifier)?.let { did -> resolveDidToPds(did)?.let { return it } }
        }
        return null
    }

    private fun resolveHandleWellKnown(handle: String): String? {
        val request = Request.Builder()
            .url("https://$handle/.well-known/atproto-did")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.trim()?.takeIf { it.startsWith("did:") }
            }
        }.getOrNull()
    }

    private fun resolveHandleViaAppView(handle: String): String? {
        val encoded = URLEncoder.encode(handle, StandardCharsets.UTF_8.toString())
        val request = Request.Builder()
            .url("https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=$encoded")
            .get()
            .header("Accept", "application/json")
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty()).optString("did").takeIf { it.startsWith("did:") }
            }
        }.getOrNull()
    }

    private fun resolveDidToPds(did: String): String? {
        val documentUrl = when {
            did.startsWith("did:plc:") -> "https://plc.directory/$did"
            did.startsWith("did:web:") -> {
                val value = did.removePrefix("did:web:")
                val parts = value.split(":")
                val host = parts.firstOrNull()?.replace("%3A", ":", ignoreCase = true) ?: return null
                if (parts.size == 1) "https://$host/.well-known/did.json"
                else "https://$host/${parts.drop(1).joinToString("/")}/did.json"
            }
            else -> return null
        }

        val request = Request.Builder().url(documentUrl).get().header("Accept", "application/json").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val services = json.optJSONArray("service") ?: JSONArray()
                for (i in 0 until services.length()) {
                    val service = services.optJSONObject(i) ?: continue
                    val id = service.optString("id")
                    val type = service.optString("type")
                    if (id.endsWith("#atproto_pds") || type == "AtprotoPersonalDataServer") {
                        val endpoint = service.optString("serviceEndpoint").removeSuffix("/")
                        if (endpoint.startsWith("https://")) return@use endpoint
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun fallbackCandidates(identifier: String): List<String> {
        val lower = identifier.lowercase()
        val preferred = when {
            lower.endsWith(".kelosocial.eu") -> listOf("https://pds.kelosocial.eu")
            lower.endsWith(".bsky.social") -> listOf("https://bsky.social")
            lower.endsWith(".wsocial.eu") -> listOf("https://pds.wsocial.eu")
            lower.endsWith(".eurosky.social") -> listOf("https://eurosky.social")
            else -> emptyList()
        }
        return preferred + listOf(
            "https://pds.kelosocial.eu",
            "https://bsky.social",
            "https://pds.wsocial.eu",
            "https://eurosky.social"
        )
    }

    fun loadSession(): AtSession? {
        val did = prefs.getString("did", null) ?: return null
        val handle = prefs.getString("handle", null) ?: return null
        val pds = prefs.getString("pds", null) ?: return null
        val access = prefs.getString("access", null) ?: return null
        return AtSession(did, handle, pds, access, prefs.getString("refresh", "").orEmpty())
    }

    fun clearSession() = prefs.edit().clear().apply()

    suspend fun sync(session: AtSession): VerificationSync = callSync(session, "sync")

    suspend fun createLinkCode(session: AtSession): VerificationSync = callSync(session, "createLinkCode")

    suspend fun consumeLinkCode(session: AtSession, code: String): VerificationSync =
        callSync(session, "consumeLinkCode", code)

    private suspend fun callSync(session: AtSession, action: String, code: String? = null): VerificationSync = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject()
            .put("action", action)
            .put("pdsUrl", session.pdsUrl)
            .put("accessJwt", session.accessJwt)
        if (code != null) bodyJson.put("code", code)

        val request = Request.Builder()
            .url(syncUrl)
            .post(bodyJson.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) clearSession()
                throw IllegalStateException("Synchronisation Kelo ID impossible.")
            }
            val data = JSONObject(raw)
            VerificationSync(
                verified = data.optBoolean("verified", false),
                shouldVerify = data.optBoolean("shouldVerify", !data.optBoolean("verified", false)),
                status = data.optString("verificationStatus", "unverified"),
                verificationType = data.optString("verificationType", "human"),
                verifiedAt = data.optString("verifiedAt").takeIf { it.isNotBlank() && it != "null" },
                linkCode = data.optString("code").takeIf { it.isNotBlank() },
                linkCodeExpiresAt = data.optString("expiresAt").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun saveSession(session: AtSession) {
        prefs.edit()
            .putString("did", session.did)
            .putString("handle", session.handle)
            .putString("pds", session.pdsUrl)
            .putString("access", session.accessJwt)
            .putString("refresh", session.refreshJwt)
            .apply()
    }
}

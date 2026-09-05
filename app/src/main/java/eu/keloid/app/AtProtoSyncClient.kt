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
import org.json.JSONObject

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

    suspend fun login(identifier: String, password: String, pdsUrl: String): AtSession = withContext(Dispatchers.IO) {
        val base = pdsUrl.trim().removeSuffix("/")
        require(base.startsWith("https://")) { "Le PDS doit utiliser HTTPS." }

        val body = JSONObject()
            .put("identifier", identifier.trim())
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
            if (!response.isSuccessful) throw IllegalStateException("Connexion AT Protocol refusée.")
            val data = JSONObject(raw)
            val session = AtSession(
                did = data.getString("did"),
                handle = data.getString("handle"),
                pdsUrl = base,
                accessJwt = data.getString("accessJwt"),
                refreshJwt = data.optString("refreshJwt")
            )
            saveSession(session)
            session
        }
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

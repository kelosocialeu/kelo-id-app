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

internal data class AtSession(val did:String,val handle:String,val pdsUrl:String,val accessJwt:String,val refreshJwt:String)
internal data class VerificationSync(val verified:Boolean,val shouldVerify:Boolean,val status:String,val verificationType:String,val verifiedAt:String?,val linkCode:String?=null,val linkCodeExpiresAt:String?=null)

internal class AtProtoSyncClient(context:Context){
 private val http=OkHttpClient(); private val jsonType="application/json; charset=utf-8".toMediaType(); private val syncUrl="https://fbtloeehynqobbwcndru.supabase.co/functions/v1/kelo-id-atproto-sync"
 private val prefs=EncryptedSharedPreferences.create(context,"kelo_id_at_session",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
 suspend fun login(identifier:String,password:String):AtSession=withContext(Dispatchers.IO){val id=identifier.trim().removePrefix("@");require(id.isNotBlank()){ "Entrez votre identifiant AT Protocol." };val candidates=buildList{detectPds(id)?.let(::add);addAll(fallbackCandidates(id))}.map{it.removeSuffix("/")}.distinct();var last:String?=null;for(base in candidates)try{return@withContext createSession(base,id,password)}catch(e:Exception){last=e.message};throw IllegalStateException(last?:"Impossible de détecter automatiquement le PDS de ce compte.")}
 private fun createSession(base:String,id:String,password:String):AtSession{require(base.startsWith("https://"));val body=JSONObject().put("identifier",id).put("password",password).toString().toRequestBody(jsonType);val r=Request.Builder().url("$base/xrpc/com.atproto.server.createSession").post(body).header("Accept","application/json").build();http.newCall(r).execute().use{res->val raw=res.body?.string().orEmpty();if(!res.isSuccessful)throw IllegalStateException("Connexion refusée par le PDS détecté.");val d=JSONObject(raw);val s=AtSession(d.getString("did"),d.getString("handle"),base,d.getString("accessJwt"),d.optString("refreshJwt"));saveSession(s);return s}}
 private fun detectPds(id:String):String?{if(id.startsWith("did:"))return resolveDidToPds(id);if(!id.contains("@")&&id.contains(".")){resolveHandleWellKnown(id)?.let{resolveDidToPds(it)?.let{return it}};resolveHandleViaAppView(id)?.let{resolveDidToPds(it)?.let{return it}}};return null}
 private fun resolveHandleWellKnown(handle:String):String?=runCatching{http.newCall(Request.Builder().url("https://$handle/.well-known/atproto-did").get().build()).execute().use{r->if(!r.isSuccessful)null else r.body?.string()?.trim()?.takeIf{it.startsWith("did:")}}}.getOrNull()
 private fun resolveHandleViaAppView(handle:String):String?=runCatching{val e=URLEncoder.encode(handle,StandardCharsets.UTF_8.toString());http.newCall(Request.Builder().url("https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=$e").get().build()).execute().use{r->if(!r.isSuccessful)null else JSONObject(r.body?.string().orEmpty()).optString("did").takeIf{it.startsWith("did:")}}}.getOrNull()
 private fun resolveDidToPds(did:String):String?{val u=when{did.startsWith("did:plc:")->"https://plc.directory/$did";did.startsWith("did:web:")->{val p=did.removePrefix("did:web:").split(":");val h=p.firstOrNull()?:return null;if(p.size==1)"https://$h/.well-known/did.json" else "https://$h/${p.drop(1).joinToString("/")}/did.json"};else->return null};return runCatching{http.newCall(Request.Builder().url(u).get().build()).execute().use{r->if(!r.isSuccessful)return@use null;val a=JSONObject(r.body?.string().orEmpty()).optJSONArray("service")?:JSONArray();for(i in 0 until a.length()){val s=a.optJSONObject(i)?:continue;if(s.optString("id").endsWith("#atproto_pds")||s.optString("type")=="AtprotoPersonalDataServer"){val e=s.optString("serviceEndpoint").removeSuffix("/");if(e.startsWith("https://"))return@use e}};null}}.getOrNull()}
 private fun fallbackCandidates(id:String):List<String>{val l=id.lowercase();val p=when{l.endsWith(".kelosocial.eu")->listOf("https://pds.kelosocial.eu");l.endsWith(".bsky.social")->listOf("https://bsky.social");l.endsWith(".wsocial.eu")->listOf("https://pds.wsocial.eu");l.endsWith(".eurosky.social")->listOf("https://eurosky.social");else->emptyList()};return p+listOf("https://pds.kelosocial.eu","https://bsky.social","https://pds.wsocial.eu","https://eurosky.social")}
 fun loadSession():AtSession?{val d=prefs.getString("did",null)?:return null;val h=prefs.getString("handle",null)?:return null;val p=prefs.getString("pds",null)?:return null;val a=prefs.getString("access",null)?:return null;return AtSession(d,h,p,a,prefs.getString("refresh","").orEmpty())}
 fun clearSession()=prefs.edit().clear().apply()
 suspend fun sync(s:AtSession)=callSync(s,"sync")
 suspend fun createLinkCode(s:AtSession)=callSync(s,"createLinkCode")
 suspend fun consumeLinkCode(s:AtSession,c:String)=callSync(s,"consumeLinkCode",c)
 private suspend fun callSync(s:AtSession,a:String,c:String?=null):VerificationSync=withContext(Dispatchers.IO){val b=JSONObject().put("action",a).put("pdsUrl",s.pdsUrl).put("accessJwt",s.accessJwt);if(c!=null)b.put("code",c);val r=Request.Builder().url(syncUrl).post(b.toString().toRequestBody(jsonType)).header("Accept","application/json").build();http.newCall(r).execute().use{res->val raw=res.body?.string().orEmpty();if(!res.isSuccessful){if(res.code==401)clearSession();throw IllegalStateException("Synchronisation Kelo ID impossible.")};val d=JSONObject(raw);val status=d.optString("verificationStatus","not_started");VerificationSync(status=="approved",status!="approved",status,d.optString("verificationType","human"),d.optString("verifiedAt").takeIf{it.isNotBlank()&&it!="null"},d.optString("code").takeIf{it.isNotBlank()},d.optString("expiresAt").takeIf{it.isNotBlank()})}}
 private fun saveSession(s:AtSession){prefs.edit().putString("did",s.did).putString("handle",s.handle).putString("pds",s.pdsUrl).putString("access",s.accessJwt).putString("refresh",s.refreshJwt).apply()}
}
package hmx.data.control

import hmx.BuildConfig
import hmx.core.error.AppError
import hmx.core.logging.HmxLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val json = Json { ignoreUnknownKeys = true }

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.arr(key: String): List<JsonObject> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

class RpcException(val code: String) : Exception(code)

class ControlClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    private var accessToken: String? = null
    private var tokenExpiresAtMs: Long = 0
    private val authMutex = Mutex()

    var credentialsProvider: (() -> Pair<String, String>)? = null

    private suspend fun ensureToken(): String {
        accessToken?.let { if (System.currentTimeMillis() < tokenExpiresAtMs - 60_000) return it }
        return withLockRefresh()
    }

    suspend fun refreshToken(): String = withLockRefresh()

    private suspend fun withLockRefresh(): String = authMutex.withLock {
        val creds = credentialsProvider?.invoke()
            ?: throw RpcException("NO_CREDENTIALS")
        val deviceId = creds.first
        val secret = creds.second
        withContext(Dispatchers.IO) {
            val body = """{"action":"token","device_id":"$deviceId","secret":"$secret"}"""
            val (code, text) = exec("POST", "functions/v1/hmx-auth", body)
            if (code != 200) throw mapHttpError(code, text)
            val obj = json.parseToJsonElement(text).jsonObject
            accessToken = obj.str("access_token")
            tokenExpiresAtMs = System.currentTimeMillis() + ((obj.str("expires_in")?.toIntOrNull() ?: 3600) * 1000L)
            accessToken!!
        }
    }

    suspend fun register(name: String, pubkey: String, secret: String): String {
        return withContext(Dispatchers.IO) {
            val hash = sha256Hex(secret)
            val body = """{"action":"register","name":${jStr(name)},"pubkey":${jStr(pubkey)},"secret":null}"""
            val bodyWithHash = body.replace("\"secret\":null", "\"secret_hash\":\"$hash\"")
            val (code, text) = exec("POST", "functions/v1/hmx-auth", bodyWithHash)
            if (code != 200) throw mapHttpError(code, text)
            json.parseToJsonElement(text).jsonObject.str("device_id")
                ?: throw RpcException("REGISTER_FAILED")
        }
    }

    suspend fun rpc(fnName: String, args: Map<String, Any>): JsonObject {
        return withContext(Dispatchers.IO) {
            var res = exec("POST", "rest/v1/rpc/$fnName", jsonArgs(args), bearer = ensureToken())
            if (res.first == 401) {
                refreshToken()
                res = exec("POST", "rest/v1/rpc/$fnName", jsonArgs(args), bearer = accessToken!!)
            }
            val code = res.first
            val text = res.second
            HmxLog.i("RPC") { "$fnName -> $code" }
            if (code >= 400) throw mapHttpError(code, text)
            json.parseToJsonElement(text).jsonObject
        }
    }

    suspend fun rpcArray(fnName: String, args: Map<String, Any> = emptyMap()): List<JsonObject> {
        return withContext(Dispatchers.IO) {
            var res = exec("POST", "rest/v1/rpc/$fnName", jsonArgs(args), bearer = ensureToken())
            if (res.first == 401) {
                refreshToken()
                res = exec("POST", "rest/v1/rpc/$fnName", jsonArgs(args), bearer = accessToken!!)
            }
            val code = res.first
            val text = res.second
            HmxLog.i("RPC") { "$fnName -> $code" }
            if (code >= 400) throw mapHttpError(code, text)
            json.parseToJsonElement(text.ifEmpty { "[]" }).jsonArray.mapNotNull { it as? JsonObject }
        }
    }

    private fun jsonArgs(args: Map<String, Any>): String =
        args.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + when (v) {
                is Int -> v.toString()
                is Long -> v.toString()
                is Boolean -> v.toString()
                else -> jStr(v.toString())
            }
        }

    private fun jStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun exec(method: String, relPath: String, body: String, bearer: String? = null): Pair<Int, String> {
        val req = Request.Builder()
            .url("$baseUrl/$relPath")
            .header("apikey", anonKey)
            .apply {
                if (bearer != null) header("Authorization", "Bearer $bearer")
                if (body.isNotEmpty() && method == "POST") {
                    post(body.toRequestBody(jsonMedia))
                    header("Content-Type", "application/json")
                }
            }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            HmxLog.d("HTTP") { "$method $relPath -> ${resp.code}" }
            return resp.code to text
        }
    }

    companion object {
        fun sha256Hex(input: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun mapHttpError(code: Int, text: String): Exception {
            val message = runCatching {
                json.parseToJsonElement(text).jsonObject.str("message")
                    ?: json.parseToJsonElement(text).jsonObject.str("error")
            }.getOrNull()
            if (message != null && code in 400..499) throw RpcException(message)
            return when (code) {
                401, 403 -> RpcException("DEVICE_UNAUTHORIZED")
                else -> RpcException(if (code >= 500) "CONTROL_PLANE_DOWN" else "HTTP_$code")
            }
        }
    }
}

fun mapRpcError(e: Throwable): AppError {
    val code = (e as? RpcException)?.code ?: e.message ?: return AppError.UNKNOWN
    return when (code.uppercase()) {
        "EXPIRED" -> AppError.PAIRING_EXPIRED
        "CONSUMED" -> AppError.PAIRING_EXPIRED
        "INVALID_CODE" -> AppError.PAIRING_EXPIRED
        "LOCKED", "TAKEN" -> AppError.PAIRING_EXPIRED
        "REJECTED" -> AppError.PAIRING_REJECTED
        "SELF_PAIR" -> AppError.PAIRING_REJECTED
        "DEVICE_REVOKED", "DEVICE_UNAUTHORIZED" -> AppError.DISCONNECTED_BY_PEER
        "RATE_LIMITED" -> AppError.TIMEOUT
        "PEER_INACTIVE" -> AppError.DISCONNECTED_BY_PEER
        "CONTROL_PLANE_DOWN" -> AppError.PROVIDER_OFFLINE
        else -> AppError.UNKNOWN
    }
}

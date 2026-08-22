package hmx.data.control

import hmx.BuildConfig
import hmx.core.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class AuthResponse(
    val device_id: String? = null,
    val access_token: String? = null,
    val expires_in: Int? = null,
    val device: DeviceInfo? = null,
    @Suppress("PropertyName") val error: String? = null,
)

@Serializable
data class DeviceInfo(val id: String, val name: String, val pubkey: String)

@Serializable
data class PeerRow(
    val peer_id: String,
    val my_role: String,
    val other_name: String,
    val other_pubkey_prefix: String,
    val net_octet: Int,
    val status: String,
    val listen_port: Int? = null,
)

@Serializable
data class PendingRequest(
    val request_id: String,
    val session_id: String,
    val requester_name: String,
    val requester_pubkey_prefix: String,
)

@Serializable
data class SessionRecord(
    val id: String,
    val peer_id: String,
    val my_role: String,
    val other_name: String,
    val mode: String,
    val started_at: String,
    val ended_at: String? = null,
    val end_reason: String? = null,
)

interface HmxRest {
    @POST("functions/v1/hmx-auth")
    suspend fun auth(@Body body: Map<String, String>): AuthResponse

    @POST("rest/v1/rpc/{fn}")
    suspend fun rpc(
        @Path("fn") fn: String,
        @Header("Authorization") bearer: String,
        @Body body: Map<String, String?>,
    ): Map<String, kotlin.Any?>

    companion object { const val RPC_PATH = "rest/v1/rpc/" }
}

// Retrofit needs Path in annotation; declared separately to keep the interface simple.
import retrofit2.http.Path

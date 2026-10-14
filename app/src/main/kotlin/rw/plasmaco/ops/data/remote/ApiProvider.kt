package rw.plasmaco.ops.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import rw.plasmaco.ops.BuildConfig
import rw.plasmaco.ops.data.repository.AppJson
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.TimeSource
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the API client for the server URL configured in Settings (HTTPS only). Returns null in standalone mode,
 * where the app works fully offline on one device.
 */
@Singleton
class ApiProvider @Inject constructor(
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    @Volatile private var cached: Pair<String, PlasmacoApi>? = null

    suspend fun api(): PlasmacoApi? {
        val url = settings.current().serverUrl
        if (!url.startsWith("https://")) return null
        cached?.let { (u, api) -> if (u == url) return api }
        val api = build(url, AppJson)
        cached = url to api
        return api
    }

    private fun build(baseUrl: String, json: Json): PlasmacoApi {
        val auth = Interceptor { chain ->
            // The token is read per request so a re-login takes effect immediately; it is never logged.
            val token = runBlocking { settings.token(time.now().toEpochMilli()) }
            val req = chain.request().newBuilder().header("Accept", "application/json").header("X-Client", "plasmaco-android/${BuildConfig.VERSION_NAME}")
            if (token != null) req.header("Authorization", "Bearer $token")
            chain.proceed(req.build())
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .build()
        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlasmacoApi::class.java)
    }
}

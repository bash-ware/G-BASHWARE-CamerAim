package com.bashworks.cameraim

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class CamerAimClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun state(settings: ConnectionSettings): DashboardState = withContext(Dispatchers.IO) {
        val baseUrl = MonitoringProtocol.normalizeBaseUrl(settings.baseUrl)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/machines/${settings.machineId}/state")
            .header("Authorization", "Bearer " + settings.readToken)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("Server je vratio HTTP " + response.code + ".")
            DashboardState.fromJson(body)
        }
    }

    suspend fun image(settings: ConnectionSettings, relativeUrl: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val baseUrl = MonitoringProtocol.normalizeBaseUrl(settings.baseUrl)
            val request = Request.Builder()
                .url(baseUrl + relativeUrl)
                .header("Authorization", "Bearer " + settings.readToken)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body.byteStream().use(BitmapFactory::decodeStream)
            }
        }
}

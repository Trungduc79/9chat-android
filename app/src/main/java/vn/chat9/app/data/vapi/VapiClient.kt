package vn.chat9.app.data.vapi

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import vn.chat9.app.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Dựng [VapiApiService] cho backend vapi. Tách hoàn toàn khỏi client 9chat:
 * base url + interceptor riêng (chỉ gắn X-API-Key, KHÔNG dùng JWT 9chat).
 * Khởi tạo lazy (xem AppContainer) → không tốn tài nguyên cho tới khi 1 module
 * quản trị thực sự gọi vapi.
 */
object VapiClient {
    fun create(): VapiApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("Accept", "application/json")
                if (BuildConfig.VAPI_API_KEY.isNotBlank()) b.header("X-API-Key", BuildConfig.VAPI_API_KEY)
                chain.proceed(b.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.VAPI_BASE_URL)            // "https://vapi.vn/api/"
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(VapiApiService::class.java)
    }
}

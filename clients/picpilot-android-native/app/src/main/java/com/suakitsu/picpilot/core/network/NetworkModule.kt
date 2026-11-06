package com.suakitsu.picpilot.core.network

import com.suakitsu.picpilot.BuildConfig
import com.suakitsu.picpilot.core.media.isPicPilotOrigin
import com.suakitsu.picpilot.core.security.SecureSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun httpClient(session: SecureSessionStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("Cookie")
        }
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = session.currentToken()
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("X-PicPilot-Client", "android-native/${BuildConfig.VERSION_NAME}")
                    .apply {
                        if (token.isNotBlank() && isPicPilotOrigin(chain.request().url)) {
                            header("Authorization", "Bearer $token")
                        } else {
                            removeHeader("Authorization")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(SafeRetryInterceptor())
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(130, TimeUnit.SECONDS)
            .writeTimeout(130, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @MediaClient
    fun mediaClient(session: SecureSessionStore): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            val token = session.currentToken()
            val request = chain.request().newBuilder()
                .header("Accept", "image/avif,image/webp,image/*,video/*,*/*;q=0.8")
                .header("X-PicPilot-Client", "android-native/${BuildConfig.VERSION_NAME}")
                .apply {
                    if (token.isNotBlank() && isPicPilotOrigin(chain.request().url)) {
                        header("Authorization", "Bearer $token")
                    } else {
                        removeHeader("Authorization")
                    }
                }
                .build()
            chain.proceed(request)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun api(client: OkHttpClient, json: Json): PicPilotApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PicPilotApi::class.java)
}

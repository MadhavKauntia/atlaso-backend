package com.atlaso.config

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.util.concurrent.TimeUnit

@Configuration
class RetrofitConfig(
    private val objectMapper: ObjectMapper,
    @Value("\${openai.api.key}") private val openAiApiKey: String,
    @Value("\${openai.api.base-url:https://api.openai.com/v1/}") private val openAiBaseUrl: String
) {

    @Bean
    fun openAiRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(openAiBaseUrl)
            .client(openAiOkHttpClient())
            .addConverterFactory(JacksonConverterFactory.create(objectMapper))
            .build()
    }

    private fun openAiOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor())
            .addInterceptor(loggingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun authInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }
}

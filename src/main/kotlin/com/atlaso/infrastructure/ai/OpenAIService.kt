package com.atlaso.infrastructure.ai

import com.fasterxml.jackson.annotation.JsonProperty
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service interface for OpenAI API.
 */
interface OpenAIService {

    @POST("chat/completions")
    fun createChatCompletion(@Body request: OpenAIRequest): Call<OpenAIResponse>
}

// OpenAI API request/response models
data class OpenAIRequest(
    val model: String,
    val messages: List<Message>,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val temperature: Double
)

data class Message(
    val role: String,
    val content: List<Content>
)

sealed interface Content

data class TextContent(
    val type: String = "text",
    val text: String
) : Content

data class ImageContent(
    val type: String = "image_url",
    @JsonProperty("image_url") val imageUrl: ImageUrl
) : Content

data class ImageUrl(val url: String)

data class OpenAIResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>,
    val usage: Usage? = null
)

data class Choice(
    val index: Int? = null,
    val message: MessageResponse,
    @JsonProperty("finish_reason") val finishReason: String? = null
)

data class MessageResponse(
    val role: String? = null,
    val content: String
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int? = null,
    @JsonProperty("completion_tokens") val completionTokens: Int? = null,
    @JsonProperty("total_tokens") val totalTokens: Int? = null
)

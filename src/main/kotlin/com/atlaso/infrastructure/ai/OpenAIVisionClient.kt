package com.atlaso.infrastructure.ai

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import retrofit2.Retrofit

/**
 * OpenAI GPT-4 Vision API client implementation using Retrofit.
 */
@Component
class OpenAIVisionClient(
    private val openAiRetrofit: Retrofit,
    @Value("\${openai.api.model:gpt-4o-mini}") private val model: String
) : VisionModelClient {

    private val logger = LoggerFactory.getLogger(OpenAIVisionClient::class.java)
    private val openAiService: OpenAIService = openAiRetrofit.create(OpenAIService::class.java)

    companion object {
        private const val SYSTEM_PROMPT = """You are a travel photo analyzer. Your ONLY job is to return valid JSON with no additional text.

Analyze the image and return ONLY this exact JSON structure:

{
  "aesthetic_score": <float 0.0-1.0>,
  "blur_score": <float 0.0-1.0>,
  "faces_count": <integer>,
  "scene_type": <one of: "people", "landscape", "food", "city", "misc">,
  "time_of_day": <one of: "day", "golden_hour", "night">,
  "dominant_colors": [<array of 2-4 hex color strings, most dominant first, e.g. "#3a7bd5">],
  "detected_objects": [<array of strings naming key objects visible, e.g. "mountain", "table", "sunset">],
  "color_temperature": <one of: "warm", "cool", "neutral">,
  "mood": <one of: "joyful", "serene", "dramatic", "adventurous">,
  "depth_of_field": <one of: "shallow", "deep">
}

Rules:
- aesthetic_score: 0.0 = low quality, 1.0 = professional/stunning
- blur_score: 0.0 = sharp, 1.0 = very blurry
- faces_count: number of human faces visible
- scene_type: primary subject of the photo
- time_of_day: lighting conditions
- dominant_colors: 2-4 most prominent colors as hex codes
- detected_objects: 3-6 key objects or subjects visible in the photo
- color_temperature: overall warmth of the photo's color palette
- mood: emotional tone of the image
- depth_of_field: shallow = blurred background with isolated subject; deep = everything in focus

Return ONLY the JSON. No markdown. No explanations. No code blocks."""
    }

    override fun analyze(imageUrl: String): String {
        logger.debug("Analyzing image: $imageUrl")

        val request = OpenAIRequest(
            model = model,
            messages = listOf(
                Message(
                    role = "system",
                    content = listOf(TextContent(text = SYSTEM_PROMPT))
                ),
                Message(
                    role = "user",
                    content = listOf(
                        TextContent(text = "Analyze this travel photo."),
                        ImageContent(imageUrl = ImageUrl(imageUrl))
                    )
                )
            ),
            maxTokens = 400,
            temperature = 0.3
        )

        val call = openAiService.createChatCompletion(request)
        val response = call.execute()

        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            logger.error("OpenAI API error: ${response.code()} - $errorBody")
            throw RuntimeException("OpenAI API error: ${response.code()} - $errorBody")
        }

        val body = response.body()
            ?: throw RuntimeException("Empty response from OpenAI")

        val content = body.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("No content in OpenAI response")

        logger.info("Successfully analyzed image, tokens used: ${body.usage?.totalTokens}")

        return content
    }
}

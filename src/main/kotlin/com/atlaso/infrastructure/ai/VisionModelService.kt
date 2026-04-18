package com.atlaso.infrastructure.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Service for analyzing photos using a vision model.
 * Implements retry logic with exponential backoff and fallback defaults.
 */
@Service
class VisionModelService(
    private val objectMapper: ObjectMapper,
    private val visionModelClient: VisionModelClient
) {
    private val logger = LoggerFactory.getLogger(VisionModelService::class.java)

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS = listOf(1000L, 2000L, 5000L) // milliseconds

        // Fallback values when all retries fail
        private val FALLBACK_RESPONSE = PhotoAnalysisResponse(
            aestheticScore = 0.5,
            blurScore = 0.5,
            facesCount = 0,
            sceneType = SceneType.MISC,
            timeOfDay = TimeOfDay.DAY
        )
    }

    /**
     * Analyzes a photo and returns structured analysis.
     *
     * @param imageUrl Public URL or base64-encoded image
     * @return PhotoAnalysisResponse with guaranteed valid values
     */
    fun analyzePhoto(imageUrl: String): PhotoAnalysisResponse {
        repeat(MAX_RETRIES) { attempt ->
            try {
                logger.debug("Attempting photo analysis (attempt ${attempt + 1}/$MAX_RETRIES)")

                val rawResponse = visionModelClient.analyze(imageUrl)
                val parsedResponse = parseResponse(rawResponse)

                logger.info("Successfully analyzed photo on attempt ${attempt + 1}")
                return parsedResponse

            } catch (e: Exception) {
                logger.warn("Photo analysis attempt ${attempt + 1} failed: ${e.message}")

                if (attempt < MAX_RETRIES - 1) {
                    val delay = RETRY_DELAYS[attempt]
                    logger.debug("Retrying in ${delay}ms...")
                    Thread.sleep(delay)
                } else {
                    logger.error("All photo analysis attempts failed, using fallback values", e)
                    return FALLBACK_RESPONSE
                }
            }
        }

        // Should never reach here, but compiler requires it
        return FALLBACK_RESPONSE
    }

    /**
     * Parses raw response from vision model.
     * Handles common issues like markdown wrapping.
     *
     * @throws Exception if parsing fails or validation fails
     */
    private fun parseResponse(rawResponse: String): PhotoAnalysisResponse {
        // Strip markdown code blocks if present
        val cleaned = rawResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        logger.debug("Cleaned response: $cleaned")

        // Parse JSON to data class
        val response = objectMapper.readValue<PhotoAnalysisResponse>(cleaned)

        // Validation happens in PhotoAnalysisResponse.init block
        // Will throw IllegalArgumentException if values are out of range

        return response
    }

    /**
     * Batch analyze multiple photos with parallel processing.
     *
     * @param imageUrls List of image URLs to analyze
     * @return Map of imageUrl to PhotoAnalysisResponse
     */
    fun analyzePhotoBatch(imageUrls: List<String>): Map<String, PhotoAnalysisResponse> {
        logger.info("Starting batch analysis of ${imageUrls.size} photos")

        return imageUrls.associateWith { url ->
            try {
                analyzePhoto(url)
            } catch (e: Exception) {
                logger.error("Failed to analyze photo: $url", e)
                FALLBACK_RESPONSE
            }
        }
    }
}

/**
 * Interface for vision model API clients.
 * Implement this for OpenAI, Claude, or other vision APIs.
 */
interface VisionModelClient {
    /**
     * Sends image to vision model and returns raw text response.
     *
     * @param imageUrl URL or base64 image
     * @return Raw response string (should be JSON)
     */
    fun analyze(imageUrl: String): String
}

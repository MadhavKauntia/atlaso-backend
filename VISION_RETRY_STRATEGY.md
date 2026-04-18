# Vision Model Retry Strategy

## Overview

The vision model analysis can fail for multiple reasons:
- Rate limits
- Temporary network issues
- Invalid JSON responses
- Model hallucinations

This document describes the retry strategy with fallback defaults.

---

## Retry Logic

### Parameters

```kotlin
MAX_RETRIES = 3
RETRY_DELAYS = [1000ms, 2000ms, 5000ms]  // Exponential backoff
```

### Flow

```
Attempt 1 → Fail → Wait 1s → Attempt 2 → Fail → Wait 2s → Attempt 3 → Fail → Return Fallback
```

---

## Failure Scenarios

### 1. Parsing Failure

**Cause:** Model returns invalid JSON

**Example:**
```
This is a beautiful photo showing a sunset...
{
  "aesthetic_score": 0.9
}
```

**Handling:**
1. Strip markdown code blocks
2. Attempt JSON parsing
3. If fails → retry
4. After max retries → fallback

**Code:**
```kotlin
private fun parseResponse(rawResponse: String): PhotoAnalysisResponse {
    val cleaned = rawResponse
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    return objectMapper.readValue<PhotoAnalysisResponse>(cleaned)
}
```

---

### 2. Validation Failure

**Cause:** Values out of range

**Example:**
```json
{
  "aesthetic_score": 1.5,  // Invalid: > 1.0
  "blur_score": -0.2,      // Invalid: < 0.0
  "faces_count": -1,       // Invalid: negative
  "scene_type": "sunset",  // Invalid: not in enum
  "time_of_day": "evening" // Invalid: not in enum
}
```

**Handling:**
1. `init` block in data class validates ranges
2. Jackson validates enum values
3. Throws exception → triggers retry
4. After max retries → fallback

**Code:**
```kotlin
data class PhotoAnalysisResponse(...) {
    init {
        require(aestheticScore in 0.0..1.0) {
            "aesthetic_score must be between 0.0 and 1.0"
        }
        require(blurScore in 0.0..1.0) {
            "blur_score must be between 0.0 and 1.0"
        }
        require(facesCount >= 0) {
            "faces_count must be non-negative"
        }
    }
}
```

---

### 3. Network Failure

**Cause:** Timeout, connection error, rate limit

**Example:**
```
java.net.SocketTimeoutException: Read timed out
```

**Handling:**
1. Catch exception
2. Log warning
3. Retry with exponential backoff
4. After max retries → fallback

---

### 4. Missing Fields

**Cause:** Model returns incomplete JSON

**Example:**
```json
{
  "aesthetic_score": 0.8,
  "blur_score": 0.1
  // Missing: faces_count, scene_type, time_of_day
}
```

**Handling:**
1. Jackson throws `MismatchedInputException`
2. Triggers retry
3. After max retries → fallback

---

## Fallback Values

When all retries fail, use conservative defaults:

```kotlin
val FALLBACK_RESPONSE = PhotoAnalysisResponse(
    aestheticScore = 0.5,   // Neutral quality
    blurScore = 0.5,        // Uncertain sharpness
    facesCount = 0,         // Conservative: no faces
    sceneType = SceneType.MISC,  // Generic category
    timeOfDay = TimeOfDay.DAY    // Most common
)
```

### Why These Values?

- **aesthetic_score = 0.5**: Middle value, doesn't bias layout selection
- **blur_score = 0.5**: Uncertain, won't auto-reject photo
- **faces_count = 0**: Conservative, won't create false positives
- **scene_type = MISC**: Generic, doesn't bias book theme
- **time_of_day = DAY**: Most common, safe default

---

## Logging Strategy

### Log Levels

**DEBUG**: Detailed processing info
```kotlin
logger.debug("Cleaned response: $cleaned")
logger.debug("Retrying in ${delay}ms...")
```

**INFO**: Successful operations
```kotlin
logger.info("Successfully analyzed photo on attempt ${attempt + 1}")
logger.info("Starting batch analysis of ${imageUrls.size} photos")
```

**WARN**: Retry attempts
```kotlin
logger.warn("Photo analysis attempt ${attempt + 1} failed: ${e.message}")
```

**ERROR**: Final failure with fallback
```kotlin
logger.error("All photo analysis attempts failed, using fallback values", e)
```

---

## Batch Processing

### Parallel Processing

```kotlin
fun analyzePhotoBatch(imageUrls: List<String>): Map<String, PhotoAnalysisResponse> {
    return imageUrls.associateWith { url ->
        try {
            analyzePhoto(url)  // Each photo gets its own retry logic
        } catch (e: Exception) {
            logger.error("Failed to analyze photo: $url", e)
            FALLBACK_RESPONSE
        }
    }
}
```

### Characteristics

- **Independent failures**: One photo failure doesn't stop batch
- **Per-photo retries**: Each photo gets full retry logic
- **Guaranteed results**: Always returns a response (real or fallback)

---

## Alternative: Async with Coroutines

For better performance with large batches:

```kotlin
suspend fun analyzePhotoBatchAsync(imageUrls: List<String>): Map<String, PhotoAnalysisResponse> {
    return coroutineScope {
        imageUrls.map { url ->
            async {
                url to try {
                    analyzePhoto(url)
                } catch (e: Exception) {
                    logger.error("Failed to analyze photo: $url", e)
                    FALLBACK_RESPONSE
                }
            }
        }.awaitAll().toMap()
    }
}
```

**Benefits:**
- Non-blocking IO
- Faster batch processing
- Better resource utilization

---

## Cost Optimization

### Token Usage

- **max_tokens = 150**: Enough for JSON response, prevents overuse
- **temperature = 0.3**: More deterministic, fewer retries needed

### Model Selection

**OpenAI:**
- `gpt-4o-mini`: $0.00015 per image (recommended for v1)
- `gpt-4o`: $0.01 per image (higher accuracy)

**Claude:**
- `claude-3-5-haiku`: ~$0.0004 per image (fastest)
- `claude-3-5-sonnet`: ~$0.003 per image (best quality)

### Retry Cost Impact

Each retry = another API call
- 3 retries = up to 3x cost
- Most photos should succeed on attempt 1
- Retries only for errors (rate limits, parsing issues)

### Batch Optimization

Process photos in batches during off-peak hours:
```kotlin
// Upload photos during day
// Analyze overnight when rate limits are less likely
```

---

## Monitoring & Alerts

### Metrics to Track

1. **Success rate**: `successful_analyses / total_attempts`
2. **Retry rate**: `retries / total_attempts`
3. **Fallback rate**: `fallbacks / total_photos`
4. **Average attempts**: `total_attempts / total_photos`
5. **Response time**: P50, P95, P99

### Alert Thresholds

- **Fallback rate > 5%**: Investigate API issues
- **Average attempts > 1.5**: Check prompt quality
- **P95 response time > 10s**: Consider timeout adjustment

---

## Testing

### Unit Tests

```kotlin
@Test
fun `should retry on JSON parsing failure`() {
    val mockClient = mock<VisionModelClient>()

    // First two attempts return invalid JSON
    whenever(mockClient.analyze(any()))
        .thenReturn("Invalid JSON")
        .thenReturn("Still invalid")
        .thenReturn("""{"aesthetic_score":0.8,...}""")

    val service = VisionModelService(objectMapper, mockClient)
    val result = service.analyzePhoto("test.jpg")

    // Should succeed on third attempt
    verify(mockClient, times(3)).analyze(any())
    assertEquals(0.8, result.aestheticScore)
}

@Test
fun `should use fallback after max retries`() {
    val mockClient = mock<VisionModelClient>()
    whenever(mockClient.analyze(any())).thenThrow(RuntimeException("API error"))

    val service = VisionModelService(objectMapper, mockClient)
    val result = service.analyzePhoto("test.jpg")

    verify(mockClient, times(MAX_RETRIES)).analyze(any())
    assertEquals(FALLBACK_RESPONSE, result)
}
```

### Integration Tests

```kotlin
@Test
fun `should handle real OpenAI API errors gracefully`() {
    val service = VisionModelService(objectMapper, realOpenAIClient)

    // Test with various failure scenarios
    // - Invalid image URL
    // - Rate limit exceeded
    // - Timeout
}
```

---

## Production Considerations

### 1. Rate Limiting

Implement client-side rate limiting:
```kotlin
@Service
class RateLimitedVisionModelService(
    private val visionModelService: VisionModelService
) {
    private val rateLimiter = RateLimiter.create(10.0) // 10 requests/second

    fun analyzePhoto(imageUrl: String): PhotoAnalysisResponse {
        rateLimiter.acquire()
        return visionModelService.analyzePhoto(imageUrl)
    }
}
```

### 2. Circuit Breaker

Prevent cascading failures:
```kotlin
@CircuitBreaker(name = "visionModel", fallbackMethod = "analyzeFallback")
fun analyzePhoto(imageUrl: String): PhotoAnalysisResponse {
    // ... analysis logic
}

fun analyzeFallback(imageUrl: String, ex: Exception): PhotoAnalysisResponse {
    logger.error("Circuit breaker triggered", ex)
    return FALLBACK_RESPONSE
}
```

### 3. Caching

Cache successful analysis results:
```kotlin
@Cacheable(value = ["photoAnalysis"], key = "#imageUrl")
fun analyzePhoto(imageUrl: String): PhotoAnalysisResponse {
    // ... analysis logic
}
```

---

## Summary

| Scenario | Retry? | Delay | Fallback |
|----------|--------|-------|----------|
| Invalid JSON | ✅ Yes | Exponential | After 3 attempts |
| Out of range values | ✅ Yes | Exponential | After 3 attempts |
| Network error | ✅ Yes | Exponential | After 3 attempts |
| Rate limit | ✅ Yes | Exponential | After 3 attempts |
| Missing fields | ✅ Yes | Exponential | After 3 attempts |

**Guaranteed Result**: Every photo gets either real analysis or safe fallback values.

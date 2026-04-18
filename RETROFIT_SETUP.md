# Retrofit HTTP Client Setup

## Overview

The Atlaso backend uses **Retrofit** for making HTTP API calls instead of Spring's RestTemplate.

**Why Retrofit?**
- ✅ Type-safe HTTP client
- ✅ Annotation-based API definitions
- ✅ Built-in support for async calls
- ✅ Excellent error handling
- ✅ Easy to test and mock
- ✅ Powered by OkHttp (efficient connection pooling)

---

## Dependencies

**Added to `build.gradle.kts`:**

```kotlin
// Retrofit for HTTP client
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-jackson:2.11.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
```

**Components:**
- `retrofit`: Core library
- `converter-jackson`: JSON serialization using Jackson (integrates with Spring's ObjectMapper)
- `okhttp`: Underlying HTTP client
- `logging-interceptor`: Request/response logging

---

## Architecture

```
OpenAIVisionClient
    ↓ uses
OpenAIService (Retrofit interface)
    ↓ configured by
RetrofitConfig (Spring @Configuration)
    ↓ creates
Retrofit instance with OkHttpClient
    ↓ includes
- Auth Interceptor (Bearer token)
- Logging Interceptor (request/response logs)
- Timeouts (30s connect, 60s read/write)
```

---

## Configuration

### 1. RetrofitConfig.kt

**Location:** `com.atlaso.config.RetrofitConfig`

```kotlin
@Configuration
class RetrofitConfig(
    private val objectMapper: ObjectMapper,
    @Value("\${openai.api.key}") private val openAiApiKey: String,
    @Value("\${openai.api.base-url}") private val openAiBaseUrl: String
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
}
```

**Key Features:**
- **Base URL**: Configured via `application.yml`
- **Jackson Integration**: Uses Spring's ObjectMapper (consistent serialization)
- **Auth Interceptor**: Automatically adds `Authorization: Bearer <token>` header
- **Logging Interceptor**: Logs full request/response bodies (DEBUG level)
- **Timeouts**: Reasonable defaults for AI API calls

---

### 2. OpenAIService.kt

**Location:** `com.atlaso.infrastructure.ai.OpenAIService`

```kotlin
interface OpenAIService {

    @POST("chat/completions")
    fun createChatCompletion(@Body request: OpenAIRequest): Call<OpenAIResponse>
}
```

**Retrofit Annotations:**
- `@POST("chat/completions")`: HTTP POST to `/v1/chat/completions`
- `@Body`: Serializes request object to JSON
- `Call<T>`: Retrofit's synchronous/async wrapper

**No implementation needed** - Retrofit generates it at runtime!

---

### 3. OpenAIVisionClient.kt

**Location:** `com.atlaso.infrastructure.ai.OpenAIVisionClient`

```kotlin
@Component
class OpenAIVisionClient(
    private val openAiRetrofit: Retrofit,
    @Value("\${openai.api.model}") private val model: String
) : VisionModelClient {

    private val openAiService: OpenAIService =
        openAiRetrofit.create(OpenAIService::class.java)

    override fun analyze(imageUrl: String): String {
        val request = OpenAIRequest(...)

        val call = openAiService.createChatCompletion(request)
        val response = call.execute()  // Synchronous call

        if (!response.isSuccessful) {
            throw RuntimeException("API error: ${response.code()}")
        }

        return response.body()?.choices?.first()?.message?.content
            ?: throw RuntimeException("No content")
    }
}
```

**Key Points:**
- Injects `Retrofit` instance (not `OpenAIService` directly)
- Creates service interface via `retrofit.create()`
- Uses `call.execute()` for synchronous execution
- Checks `response.isSuccessful` before accessing body
- Logs token usage from response

---

## Application Configuration

**File:** `src/main/resources/application.yml`

```yaml
openai:
  api:
    key: ${OPENAI_API_KEY:your-api-key-here}
    model: gpt-4o-mini
    base-url: https://api.openai.com/v1/

logging:
  level:
    okhttp3.OkHttpClient: DEBUG  # Enable HTTP logging
```

**Environment Variable:**
```bash
export OPENAI_API_KEY="sk-..."
```

Or in `.env` file:
```
OPENAI_API_KEY=sk-...
```

---

## Request Flow

### Example: Analyze Photo

```
1. Client calls analyze("https://example.com/photo.jpg")
   ↓
2. Build OpenAIRequest with messages
   ↓
3. Call openAiService.createChatCompletion(request)
   ↓
4. Retrofit creates Call<OpenAIResponse>
   ↓
5. OkHttp adds Authorization header (via interceptor)
   ↓
6. OkHttp logs request (via logging interceptor)
   ↓
7. HTTP POST to https://api.openai.com/v1/chat/completions
   ↓
8. Receive HTTP 200 response
   ↓
9. OkHttp logs response (via logging interceptor)
   ↓
10. Jackson deserializes JSON → OpenAIResponse
   ↓
11. Extract content from response.choices[0].message.content
   ↓
12. Return JSON string
```

---

## Error Handling

### HTTP Errors

```kotlin
val response = call.execute()

if (!response.isSuccessful) {
    val errorBody = response.errorBody()?.string()
    logger.error("OpenAI API error: ${response.code()} - $errorBody")
    throw RuntimeException("OpenAI API error: ${response.code()}")
}
```

**Common Status Codes:**
- `401`: Invalid API key
- `429`: Rate limit exceeded
- `500`: OpenAI server error
- `503`: Service unavailable

### Network Errors

```kotlin
try {
    val response = call.execute()
    // ...
} catch (e: IOException) {
    logger.error("Network error calling OpenAI", e)
    throw RuntimeException("Network error", e)
}
```

**Common Exceptions:**
- `SocketTimeoutException`: Request timeout
- `UnknownHostException`: DNS resolution failed
- `ConnectException`: Connection refused

---

## Logging

### Request Logging

With `HttpLoggingInterceptor.Level.BODY`:

```
--> POST https://api.openai.com/v1/chat/completions
Content-Type: application/json
Authorization: Bearer sk-***
Content-Length: 523

{
  "model": "gpt-4o-mini",
  "messages": [
    {
      "role": "system",
      "content": [{"type": "text", "text": "You are a travel photo analyzer..."}]
    },
    ...
  ],
  "max_tokens": 150,
  "temperature": 0.3
}
--> END POST
```

### Response Logging

```
<-- 200 OK https://api.openai.com/v1/chat/completions (1234ms)
Content-Type: application/json
Content-Length: 342

{
  "id": "chatcmpl-abc123",
  "model": "gpt-4o-mini",
  "choices": [
    {
      "message": {
        "content": "{\"aesthetic_score\": 0.85, ...}"
      }
    }
  ],
  "usage": {
    "total_tokens": 125
  }
}
<-- END HTTP
```

---

## Async Support (Future)

Retrofit supports async calls out of the box:

```kotlin
// Synchronous (current)
val response = call.execute()

// Asynchronous (future)
call.enqueue(object : Callback<OpenAIResponse> {
    override fun onResponse(call: Call<OpenAIResponse>, response: Response<OpenAIResponse>) {
        // Handle success
    }

    override fun onFailure(call: Call<OpenAIResponse>, t: Throwable) {
        // Handle error
    }
})
```

Or with Kotlin Coroutines (add dependency):

```kotlin
// build.gradle.kts
implementation("com.squareup.retrofit2:converter-jackson:2.11.0")

// Service interface
interface OpenAIService {
    @POST("chat/completions")
    suspend fun createChatCompletion(@Body request: OpenAIRequest): OpenAIResponse
}

// Client
suspend fun analyze(imageUrl: String): String {
    val response = openAiService.createChatCompletion(request)
    return response.choices.first().message.content
}
```

---

## Testing

### Mock Retrofit Service

```kotlin
@Test
fun `should analyze photo successfully`() {
    val mockService = mock<OpenAIService>()
    val mockCall = mock<Call<OpenAIResponse>>()
    val mockResponse = Response.success(
        OpenAIResponse(
            choices = listOf(
                Choice(
                    message = MessageResponse(
                        content = """{"aesthetic_score": 0.8, ...}"""
                    )
                )
            )
        )
    )

    whenever(mockService.createChatCompletion(any())).thenReturn(mockCall)
    whenever(mockCall.execute()).thenReturn(mockResponse)

    // Test client
    val client = OpenAIVisionClient(mockService, "gpt-4o-mini")
    val result = client.analyze("https://example.com/photo.jpg")

    assertEquals("""{"aesthetic_score": 0.8, ...}""", result)
}
```

### Integration Test

```kotlin
@SpringBootTest
class OpenAIVisionClientIntegrationTest {

    @Autowired
    private lateinit var client: OpenAIVisionClient

    @Test
    fun `should call real OpenAI API`() {
        val result = client.analyze("https://example.com/photo.jpg")
        assertNotNull(result)
    }
}
```

---

## Advantages over RestTemplate

| Feature | RestTemplate | Retrofit |
|---------|-------------|----------|
| Type Safety | ❌ Manual casting | ✅ Compile-time checking |
| API Definition | ❌ Scattered in code | ✅ Centralized interface |
| Async Support | ⚠️ Requires WebClient | ✅ Built-in |
| Error Handling | ⚠️ Try-catch | ✅ Response wrapper |
| Mocking | ⚠️ MockRestServiceServer | ✅ Easy interface mocking |
| Code Clarity | ❌ Verbose | ✅ Concise |
| Performance | ✅ Good | ✅ Better (OkHttp) |

---

## Summary

**Retrofit Setup:**
1. ✅ Dependencies added to `build.gradle.kts`
2. ✅ `RetrofitConfig` creates configured Retrofit instance
3. ✅ `OpenAIService` defines API endpoints (type-safe interface)
4. ✅ `OpenAIVisionClient` uses service to make calls
5. ✅ Automatic header injection (auth, content-type)
6. ✅ Request/response logging for debugging
7. ✅ Proper timeout configuration

**Result:** Clean, type-safe, testable HTTP client with minimal boilerplate!

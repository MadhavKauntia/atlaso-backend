# Vision Model Prompt Design

## System Prompt

```
You are a travel photo analyzer. Your ONLY job is to return valid JSON with no additional text.

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
  "depth_of_field": <one of: "shallow", "deep">,
  "location_tag": <one of: "beach", "mountain", "forest", "lake", "waterfall", "desert", "park", "restaurant", "cafe", "market", "hotel", "airport", "city_street", "historical_site", "museum", "temple", "viewpoint", "indoor_venue", "boat", "other">,
  "subject_type": <one of: "person", "couple", "group", "landscape", "food", "object", "architecture", "activity", "other">,
  "shot_distance": <one of: "closeup", "medium", "wide">,
  "subject_prominence": <one of: "low", "medium", "high">,
  "setting_scope": <one of: "detail", "subject", "environment">,
  "background_complexity": <one of: "low", "medium", "high">,
  "negative_space": <one of: "low", "medium", "high">,
  "keepsake_interest": <float 0.0-1.0>,
  "primary_subject": <short lowercase noun phrase, 1-4 words>
}

Rules:
- aesthetic_score: 0.0 = low quality, 1.0 = professional/stunning
- blur_score: 0.0 = sharp, 1.0 = very blurry
- faces_count: number of human faces visible
- scene_type: the primary scene category. MUST be EXACTLY one of: people, landscape, food, city, misc — never any other word. This is NOT the same as subject_type; never put a subject_type value here (e.g. "activity", "object", "architecture"). If people are the focus, including people doing an activity, use "people"; buildings/streets/monuments → "city"; nature/scenery → "landscape"; meals/drinks → "food"; anything else → "misc".
- time_of_day: lighting conditions
- dominant_colors: 2-4 most prominent colors as hex codes
- detected_objects: 3-6 key objects or subjects visible in the photo
- color_temperature: overall warmth of the photo's color palette
- mood: emotional tone of the image
- depth_of_field: shallow = blurred background with isolated subject; deep = everything in focus
- location_tag: the type of place or setting depicted; choose the single best match
- subject_type: the main subject category of the photo
- shot_distance: closeup = tight on subject; medium = subject with surroundings; wide = expansive / distant
- subject_prominence: how dominant the main subject is in the frame
- setting_scope: detail = small detail; subject = subject in context; environment = the whole place/scene
- background_complexity: how busy/cluttered the background is
- negative_space: amount of clean empty space around the subject
- keepsake_interest: how much this photo reads as a memorable travel keepsake versus a mundane or utility snapshot. HIGH (0.8-1.0): scenic vistas, landmarks, temples, wildlife, people enjoying the trip, a signature local dish plated as an experience, a distinctive cultural moment. LOW (0.0-0.3): utility/incidental shots you'd never frame — a bike lock or its combination, shoes on the floor, a generic storefront or shopfront sign, a supermarket aisle, a lone disposable coffee cup, parking, receipts, transit screens, a plain hotel-room detail. MEDIUM (0.4-0.7): ordinary but pleasant travel shots. Judge memorability, NOT technical quality — a sharp, well-exposed photo of a bike lock is still LOW.
- primary_subject: a short, stable label naming the single main subject, lowercase, 1-4 words, generic enough to be identical across near-identical photos of the same thing (e.g. "coconut drink", "rice terrace", "temple gateway", "beach sunset", "ramen bowl"). For photos whose main subject is one or more people, always use exactly "people".

Describe observable properties objectively. The ONLY subjective judgment allowed is keepsake_interest (memorability). Do not recommend layouts.
Return ONLY the JSON. No markdown. No explanations. No code blocks.
```

## User Prompt

```
Analyze this travel photo.
```

---

## Example Valid Responses

### Example 1: Beach Sunset
```json
{
  "aesthetic_score": 0.92,
  "blur_score": 0.1,
  "faces_count": 0,
  "scene_type": "landscape",
  "time_of_day": "golden_hour"
}
```

### Example 2: Group Dinner
```json
{
  "aesthetic_score": 0.65,
  "blur_score": 0.3,
  "faces_count": 4,
  "scene_type": "people",
  "time_of_day": "night"
}
```

### Example 3: Street Food
```json
{
  "aesthetic_score": 0.78,
  "blur_score": 0.15,
  "faces_count": 0,
  "scene_type": "food",
  "time_of_day": "day"
}
```

### Example 4: City Architecture
```json
{
  "aesthetic_score": 0.85,
  "blur_score": 0.05,
  "faces_count": 2,
  "scene_type": "city",
  "time_of_day": "day"
}
```

---

## Field Definitions

### aesthetic_score (0.0 - 1.0)
Evaluate based on:
- **Composition**: Rule of thirds, leading lines, framing
- **Lighting**: Proper exposure, dynamic range
- **Color**: Vibrant but natural, good white balance
- **Subject clarity**: Clear focal point
- **Technical quality**: Sharpness, noise level

**Scoring guide:**
- 0.0 - 0.3: Poor (bad lighting, poor composition, technical issues)
- 0.4 - 0.6: Average (acceptable snapshot quality)
- 0.7 - 0.8: Good (well-composed, good lighting)
- 0.9 - 1.0: Excellent (professional quality, stunning)

### blur_score (0.0 - 1.0)
Measure image sharpness:
- **0.0**: Perfectly sharp, crisp details
- **0.0 - 0.2**: Slightly soft but acceptable
- **0.3 - 0.5**: Noticeably blurry (motion blur or out of focus)
- **0.6 - 0.8**: Very blurry, unusable for print
- **0.9 - 1.0**: Completely out of focus

### faces_count (integer ≥ 0)
Count visible human faces:
- Must be recognizable as a face
- Partially visible faces count (e.g., profile)
- Distant faces in crowds: estimate conservatively
- Non-human faces (statues, art): do NOT count

### scene_type (enum)
Primary subject classification:
- **people**: Human subjects are the main focus (portraits, group photos, selfies)
- **landscape**: Nature scenes (mountains, beaches, forests, sunsets)
- **food**: Meals, dishes, drinks, culinary focus
- **city**: Urban environments (buildings, streets, architecture, monuments)
- **misc**: Everything else (animals, interiors, abstract, unclear)

### time_of_day (enum)
Lighting conditions:
- **day**: Bright daylight, midday sun, clear lighting
- **golden_hour**: Warm, soft light (sunrise/sunset, 1 hour before/after)
- **night**: Dark conditions (street lights, indoor artificial light, stars)

---

## OpenAI API Usage Example

### Request Structure

```json
{
  "model": "gpt-4o-mini",
  "messages": [
    {
      "role": "system",
      "content": "You are a travel photo analyzer. Your ONLY job is to return valid JSON with no additional text.\n\nAnalyze the image and return ONLY this exact JSON structure:\n\n{\n  \"aesthetic_score\": <float 0.0-1.0>,\n  \"blur_score\": <float 0.0-1.0>,\n  \"faces_count\": <integer>,\n  \"scene_type\": <one of: \"people\", \"landscape\", \"food\", \"city\", \"misc\">,\n  \"time_of_day\": <one of: \"day\", \"golden_hour\", \"night\">\n}\n\nRules:\n- aesthetic_score: 0.0 = low quality, 1.0 = professional/stunning\n- blur_score: 0.0 = sharp, 1.0 = very blurry\n- faces_count: number of human faces visible\n- scene_type: primary subject of the photo\n- time_of_day: lighting conditions\n\nReturn ONLY the JSON. No markdown. No explanations. No code blocks."
    },
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Analyze this travel photo."
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "https://example.com/photo.jpg"
          }
        }
      ]
    }
  ],
  "max_tokens": 150,
  "temperature": 0.3
}
```

### Configuration Notes

- **max_tokens**: 150 is enough for JSON response
- **temperature**: 0.3 for more consistent, deterministic output
- **model**: `gpt-4o-mini` for cost-effective vision analysis
  - Alternative: `gpt-4o` for higher accuracy (more expensive)

---

## Anthropic Claude API Usage Example

### Request Structure

```json
{
  "model": "claude-3-5-haiku-20241022",
  "max_tokens": 150,
  "temperature": 0.3,
  "system": "You are a travel photo analyzer. Your ONLY job is to return valid JSON with no additional text.\n\nAnalyze the image and return ONLY this exact JSON structure:\n\n{\n  \"aesthetic_score\": <float 0.0-1.0>,\n  \"blur_score\": <float 0.0-1.0>,\n  \"faces_count\": <integer>,\n  \"scene_type\": <one of: \"people\", \"landscape\", \"food\", \"city\", \"misc\">,\n  \"time_of_day\": <one of: \"day\", \"golden_hour\", \"night\">\n}\n\nRules:\n- aesthetic_score: 0.0 = low quality, 1.0 = professional/stunning\n- blur_score: 0.0 = sharp, 1.0 = very blurry\n- faces_count: number of human faces visible\n- scene_type: primary subject of the photo\n- time_of_day: lighting conditions\n\nReturn ONLY the JSON. No markdown. No explanations. No code blocks.",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "image",
          "source": {
            "type": "url",
            "url": "https://example.com/photo.jpg"
          }
        },
        {
          "type": "text",
          "text": "Analyze this travel photo."
        }
      ]
    }
  ]
}
```

### Model Options

- **claude-3-5-haiku-20241022**: Fastest, cheapest, good for batch processing
- **claude-3-5-sonnet-20241022**: Best balance of speed/quality
- **claude-3-7-sonnet-20250219**: Highest quality vision analysis

---

## Why This Prompt Works

1. **Clear Constraints**: "ONLY job", "No markdown", "No explanations"
2. **Exact Schema**: Shows the exact JSON structure expected
3. **Field Descriptions**: Explains what each field means
4. **Low Temperature**: More deterministic output (0.3)
5. **Token Limit**: Prevents verbose responses
6. **Simple User Prompt**: Vision models need minimal instruction

---

## Common Issues & Solutions

### Issue 1: Model Returns Markdown

**Bad response:**
```
```json
{
  "aesthetic_score": 0.8
}
```
```

**Solution:** Strip markdown code blocks in parsing code:
```kotlin
val cleaned = response.trim().removePrefix("```json").removeSuffix("```").trim()
```

### Issue 2: Model Adds Explanations

**Bad response:**
```json
{
  "aesthetic_score": 0.8,
  "comment": "This is a beautiful sunset photo..."
}
```

**Solution:** Kotlin data class will ignore unknown fields (Jackson default behavior)

### Issue 3: Invalid Enum Values

**Bad response:**
```json
{
  "scene_type": "sunset"
}
```

**Solution:** Caught by Jackson deserialization, triggers retry

### Issue 4: Missing Fields

**Bad response:**
```json
{
  "aesthetic_score": 0.8
}
```

**Solution:** Jackson will throw exception, triggers retry with fallback

---

## Testing Strategy

### Test Cases

1. **Valid JSON**: Verify successful parsing
2. **Markdown wrapped**: Test stripping logic
3. **Extra fields**: Ensure ignored
4. **Invalid enum**: Verify retry
5. **Missing fields**: Verify fallback
6. **Malformed JSON**: Verify error handling

### Unit Test Example

```kotlin
@Test
fun `should parse valid response`() {
    val json = """
    {
      "aesthetic_score": 0.85,
      "blur_score": 0.1,
      "faces_count": 2,
      "scene_type": "landscape",
      "time_of_day": "golden_hour"
    }
    """.trimIndent()

    val response = objectMapper.readValue<PhotoAnalysisResponse>(json)

    assertEquals(0.85, response.aestheticScore)
    assertEquals(SceneType.LANDSCAPE, response.sceneType)
}

@Test
fun `should reject out of range scores`() {
    val json = """{"aesthetic_score": 1.5, ...}"""

    assertThrows<IllegalArgumentException> {
        objectMapper.readValue<PhotoAnalysisResponse>(json)
    }
}
```

# Photo Selection Algorithm for Travel Photobooks

## Overview

This algorithm selects the best photos for a 30-page photobook using:
1. **Quality Filtering**: Remove blurry/low-quality photos
2. **Burst Detection**: Identify and cap rapid-fire shots
3. **Weighted Scoring**: Rank photos by multiple factors
4. **Diversity Selection**: Ensure variety in scenes and subjects

**Design Principles:**
- Deterministic (same input → same output)
- Explainable (can show why each photo was selected)
- Configurable (no hardcoded magic numbers)
- Debuggable (intermediate scores visible)

---

## Phase 1: Quality Filtering

### Hard Cutoffs

Remove photos that fail minimum quality thresholds:

```kotlin
data class QualityThresholds(
    val minAestheticScore: Double = 0.4,    // Below this = amateur/poor quality
    val maxBlurScore: Double = 0.6,         // Above this = too blurry for print
    val minDimension: Int = 1200            // Minimum width or height for print quality
)

fun filterLowQuality(photos: List<Photo>, thresholds: QualityThresholds): List<Photo> {
    return photos.filter { photo ->
        val signals = photo.signals ?: return@filter false
        val metadata = photo.metadata

        // Must have AI analysis
        signals != null &&

        // Quality checks
        signals.aestheticScore >= thresholds.minAestheticScore &&
        signals.blurScore <= thresholds.maxBlurScore &&
        metadata.width >= thresholds.minDimension &&
        metadata.height >= thresholds.minDimension
    }
}
```

**Rationale:**
- **0.4 aesthetic threshold**: Excludes bottom 40% of photos (bad composition, poor lighting)
- **0.6 blur threshold**: Noticeable blur but not completely unusable
- **1200px minimum**: Ensures print quality at standard book sizes

**Result:** Eliminates ~30-40% of typical travel photo sets

---

## Phase 2: Burst Detection

### Problem

Users often take 5-10 similar photos in rapid succession (bursts).
We want the **best one** from each burst, not all of them.

### Algorithm

```kotlin
data class BurstConfig(
    val timeWindowSeconds: Long = 10,      // Photos within 10s are considered a burst
    val maxPhotosPerBurst: Int = 1         // Keep only 1 photo per burst
)

data class Burst(
    val photos: List<Photo>,
    val startTime: Instant,
    val endTime: Instant
) {
    fun getBestPhoto(): Photo {
        // Within a burst, choose highest aesthetic score
        return photos.maxByOrNull { it.signals?.aestheticScore ?: 0.0 }!!
    }
}

fun detectBursts(photos: List<Photo>, config: BurstConfig): List<Burst> {
    // Sort by capture time
    val sorted = photos.sortedBy { it.metadata.takenAt }

    val bursts = mutableListOf<Burst>()
    var currentBurst = mutableListOf<Photo>()
    var burstStartTime: Instant? = null

    sorted.forEach { photo ->
        val photoTime = photo.metadata.takenAt ?: return@forEach

        if (burstStartTime == null) {
            // Start first burst
            burstStartTime = photoTime
            currentBurst.add(photo)
        } else {
            val timeSinceStart = Duration.between(burstStartTime, photoTime).seconds

            if (timeSinceStart <= config.timeWindowSeconds) {
                // Same burst
                currentBurst.add(photo)
            } else {
                // New burst
                if (currentBurst.size > 1) {
                    bursts.add(Burst(
                        photos = currentBurst.toList(),
                        startTime = burstStartTime!!,
                        endTime = currentBurst.last().metadata.takenAt!!
                    ))
                }

                // Start new burst
                burstStartTime = photoTime
                currentBurst = mutableListOf(photo)
            }
        }
    }

    // Add final burst
    if (currentBurst.size > 1) {
        bursts.add(Burst(
            photos = currentBurst.toList(),
            startTime = burstStartTime!!,
            endTime = currentBurst.last().metadata.takenAt!!
        ))
    }

    return bursts
}

fun dedupeBursts(photos: List<Photo>, config: BurstConfig): List<Photo> {
    val bursts = detectBursts(photos, config)
    val burstPhotoIds = bursts.flatMap { it.photos.map { p -> p.id } }.toSet()
    val bestFromBursts = bursts.map { it.getBestPhoto() }
    val nonBurstPhotos = photos.filter { it.id !in burstPhotoIds }

    return nonBurstPhotos + bestFromBursts
}
```

**Example:**
```
Input:  [Photo1@10:00:00, Photo2@10:00:03, Photo3@10:00:07, Photo4@10:05:00]
Bursts: [[Photo1, Photo2, Photo3]]  ← 7 seconds apart
Output: [Photo2 (best aesthetic), Photo4]
```

**Result:** Reduces photo count by ~20-30% while keeping best shots

---

## Phase 3: Weighted Scoring

### Scoring Formula

Each photo gets a composite score based on multiple factors:

```kotlin
data class ScoringWeights(
    // Primary quality factors
    val aestheticWeight: Double = 0.50,       // 50% - Most important
    val sharpnessWeight: Double = 0.20,       // 20% - Sharp photos matter

    // Diversity bonuses
    val sceneVarietyBonus: Double = 0.10,     // 10% - Encourage different scenes
    val timeVarietyBonus: Double = 0.05,      // 5%  - Mix of day/night

    // Preference factors
    val orientationPreference: Double = 0.10,  // 10% - Landscape photos preferred
    val lightingPreference: Double = 0.05      // 5%  - Daylight/golden hour preferred
)

data class PhotoScore(
    val photo: Photo,
    val totalScore: Double,
    val breakdown: ScoreBreakdown  // For debugging
)

data class ScoreBreakdown(
    val aestheticScore: Double,
    val sharpnessScore: Double,
    val sceneVarietyScore: Double,
    val timeVarietyScore: Double,
    val orientationScore: Double,
    val lightingScore: Double
)

fun scorePhoto(
    photo: Photo,
    weights: ScoringWeights,
    sceneTypeCounts: Map<String, Int>,    // How many of each scene type already selected
    timeOfDayCounts: Map<String, Int>     // How many of each time already selected
): PhotoScore {
    val signals = photo.signals!!
    val metadata = photo.metadata

    // 1. Aesthetic Score (0.0 - 1.0, normalized)
    val aestheticScore = signals.aestheticScore * weights.aestheticWeight

    // 2. Sharpness Score (inverse of blur, 0.0 - 1.0)
    val sharpnessScore = (1.0 - signals.blurScore) * weights.sharpnessWeight

    // 3. Scene Variety Bonus (less common scenes get bonus)
    val sceneCount = sceneTypeCounts[signals.sceneType] ?: 0
    val sceneVarietyScore = calculateVarietyBonus(sceneCount) * weights.sceneVarietyBonus

    // 4. Time Variety Bonus (less common times get bonus)
    val timeCount = timeOfDayCounts[signals.timeOfDay] ?: 0
    val timeVarietyScore = calculateVarietyBonus(timeCount) * weights.timeVarietyBonus

    // 5. Orientation Preference (landscape > portrait > square)
    val orientationScore = when (metadata.orientation) {
        Orientation.LANDSCAPE -> 1.0
        Orientation.PORTRAIT -> 0.7
        Orientation.SQUARE -> 0.5
    } * weights.orientationPreference

    // 6. Lighting Preference (golden_hour > day > night)
    val lightingScore = when (signals.timeOfDay) {
        "golden_hour" -> 1.0
        "day" -> 0.8
        "night" -> 0.6
        else -> 0.5
    } * weights.lightingPreference

    val totalScore = aestheticScore + sharpnessScore + sceneVarietyScore +
                     timeVarietyScore + orientationScore + lightingScore

    return PhotoScore(
        photo = photo,
        totalScore = totalScore,
        breakdown = ScoreBreakdown(
            aestheticScore = aestheticScore,
            sharpnessScore = sharpnessScore,
            sceneVarietyScore = sceneVarietyScore,
            timeVarietyScore = timeVarietyScore,
            orientationScore = orientationScore,
            lightingScore = lightingScore
        )
    )
}

/**
 * Returns a bonus inversely proportional to count.
 * Encourages selecting underrepresented categories.
 *
 * Examples:
 * - 0 photos of this type → bonus = 1.0
 * - 5 photos of this type → bonus = 0.5
 * - 10+ photos of this type → bonus = 0.0
 */
fun calculateVarietyBonus(count: Int): Double {
    return when {
        count == 0 -> 1.0
        count <= 2 -> 0.9
        count <= 5 -> 0.7
        count <= 10 -> 0.4
        else -> 0.0
    }
}

enum class Orientation {
    LANDSCAPE,  // width > height
    PORTRAIT,   // height > width
    SQUARE;     // width ≈ height

    companion object {
        fun from(width: Int, height: Int): Orientation {
            val ratio = width.toDouble() / height
            return when {
                ratio > 1.1 -> LANDSCAPE
                ratio < 0.9 -> PORTRAIT
                else -> SQUARE
            }
        }
    }
}
```

### Score Interpretation

**Total Score Range:** 0.0 - 1.0 (all weights sum to 1.0)

**Example Breakdown:**
```
Photo: Sunset beach landscape
├─ Aesthetic: 0.85 × 0.50 = 0.425
├─ Sharpness: 0.90 × 0.20 = 0.180
├─ Scene Variety: 0.70 × 0.10 = 0.070  (few landscapes so far)
├─ Time Variety: 0.90 × 0.05 = 0.045  (no golden hour yet)
├─ Orientation: 1.00 × 0.10 = 0.100  (landscape)
└─ Lighting: 1.00 × 0.05 = 0.050     (golden hour)
─────────────────────────────────────
Total: 0.870
```

---

## Phase 4: Diversity-Aware Selection

### Problem

Simply taking top 30 scored photos might result in:
- All landscape photos (if that's what scores highest)
- No night photos
- No people photos

### Solution: Bucket Selection

```kotlin
data class DiversityConfig(
    val targetPhotos: Int = 30,

    // Scene type distribution (should sum to ~1.0)
    val sceneTypeTargets: Map<String, Double> = mapOf(
        "people" to 0.30,      // 30% people photos (~9 photos)
        "landscape" to 0.35,   // 35% landscapes (~10-11 photos)
        "city" to 0.15,        // 15% urban (~4-5 photos)
        "food" to 0.10,        // 10% food (~3 photos)
        "misc" to 0.10         // 10% other (~3 photos)
    ),

    // Time of day distribution
    val timeTargets: Map<String, Double> = mapOf(
        "day" to 0.60,         // 60% daytime
        "golden_hour" to 0.25, // 25% golden hour
        "night" to 0.15        // 15% night
    )
)

fun selectPhotos(
    photos: List<Photo>,
    weights: ScoringWeights,
    diversityConfig: DiversityConfig
): List<Photo> {
    val selected = mutableListOf<Photo>()
    val sceneTypeCounts = mutableMapOf<String, Int>()
    val timeOfDayCounts = mutableMapOf<String, Int>()

    // Calculate target counts per category
    val sceneTargets = diversityConfig.sceneTypeTargets.mapValues { (_, ratio) ->
        (diversityConfig.targetPhotos * ratio).toInt()
    }

    // Score all photos initially
    var scoredPhotos = photos.map { photo ->
        scorePhoto(photo, weights, sceneTypeCounts, timeOfDayCounts)
    }.sortedByDescending { it.totalScore }

    // Iteratively select photos
    while (selected.size < diversityConfig.targetPhotos && scoredPhotos.isNotEmpty()) {
        // Find next best photo
        val nextPhoto = scoredPhotos.first()
        val signals = nextPhoto.photo.signals!!

        // Check if we've hit scene type cap
        val sceneCount = sceneTypeCounts[signals.sceneType] ?: 0
        val sceneTarget = sceneTargets[signals.sceneType] ?: 3

        if (sceneCount < sceneTarget) {
            // Add photo
            selected.add(nextPhoto.photo)
            sceneTypeCounts[signals.sceneType] = sceneCount + 1
            timeOfDayCounts[signals.timeOfDay] = (timeOfDayCounts[signals.timeOfDay] ?: 0) + 1

            // Re-score remaining photos with updated counts
            scoredPhotos = scoredPhotos.drop(1).map { photoScore ->
                scorePhoto(photoScore.photo, weights, sceneTypeCounts, timeOfDayCounts)
            }.sortedByDescending { it.totalScore }
        } else {
            // Skip this photo, it would exceed scene type quota
            scoredPhotos = scoredPhotos.drop(1)
        }
    }

    return selected
}
```

### How It Works

1. **Initial Scoring**: Score all photos with empty counts (no diversity bonus yet)
2. **Iterative Selection**:
   - Pick highest-scored photo
   - Check if adding it would exceed scene type quota
   - If yes: skip it
   - If no: add it and re-score remaining photos (variety bonuses change)
3. **Dynamic Re-scoring**: As we select more landscapes, their variety bonus decreases, allowing other scene types to rise in rankings

**Example:**
```
Iteration 1:
  Top photo: Landscape (score 0.87)
  Landscape count: 0 / 11 target ✓ Add it

Iteration 2:
  Top photo: Landscape (score 0.85)
  Landscape count: 1 / 11 target ✓ Add it

Iteration 8:
  Top photo: Landscape (score 0.82)
  Landscape count: 10 / 11 target ✓ Add it

Iteration 9:
  Top photo: Landscape (score 0.81)
  Landscape count: 11 / 11 target ✗ Skip
  Next photo: People (score 0.78) ✓ Add it
```

---

## Complete Algorithm

### Main Function

```kotlin
class PhotoSelector(
    private val qualityThresholds: QualityThresholds = QualityThresholds(),
    private val burstConfig: BurstConfig = BurstConfig(),
    private val scoringWeights: ScoringWeights = ScoringWeights(),
    private val diversityConfig: DiversityConfig = DiversityConfig()
) {

    fun selectPhotosForBook(photos: List<Photo>): PhotoSelectionResult {
        val log = mutableListOf<String>()

        // Phase 1: Quality Filtering
        log.add("Starting with ${photos.size} photos")
        val qualityPhotos = filterLowQuality(photos, qualityThresholds)
        log.add("After quality filter: ${qualityPhotos.size} photos")

        // Phase 2: Burst Detection
        val dedupedPhotos = dedupeBursts(qualityPhotos, burstConfig)
        log.add("After burst deduplication: ${dedupedPhotos.size} photos")

        // Phase 3 & 4: Scoring + Diversity Selection
        val selectedPhotos = selectPhotos(dedupedPhotos, scoringWeights, diversityConfig)
        log.add("Final selection: ${selectedPhotos.size} photos")

        // Sort by chronological order for the book
        val chronological = selectedPhotos.sortedBy { it.metadata.takenAt }

        return PhotoSelectionResult(
            photos = chronological,
            logs = log,
            stats = calculateStats(chronological)
        )
    }

    private fun calculateStats(photos: List<Photo>): SelectionStats {
        val sceneTypeCounts = photos.groupingBy { it.signals?.sceneType }.eachCount()
        val timeOfDayCounts = photos.groupingBy { it.signals?.timeOfDay }.eachCount()
        val avgAesthetic = photos.mapNotNull { it.signals?.aestheticScore }.average()
        val avgBlur = photos.mapNotNull { it.signals?.blurScore }.average()

        return SelectionStats(
            totalPhotos = photos.size,
            sceneTypeDistribution = sceneTypeCounts,
            timeOfDayDistribution = timeOfDayCounts,
            averageAestheticScore = avgAesthetic,
            averageBlurScore = avgBlur
        )
    }
}

data class PhotoSelectionResult(
    val photos: List<Photo>,
    val logs: List<String>,
    val stats: SelectionStats
)

data class SelectionStats(
    val totalPhotos: Int,
    val sceneTypeDistribution: Map<String?, Int>,
    val timeOfDayDistribution: Map<String?, Int>,
    val averageAestheticScore: Double,
    val averageBlurScore: Double
)
```

---

## Example Output

### Input
```
100 photos from Greece trip
├─ 30 landscape photos (beaches, sunsets)
├─ 25 people photos (friends, selfies)
├─ 20 city photos (architecture)
├─ 15 food photos (restaurants)
└─ 10 misc photos
```

### Processing Log
```
Starting with 100 photos
After quality filter: 78 photos (removed 22 blurry/low quality)
After burst deduplication: 52 photos (removed 26 burst duplicates)
Final selection: 30 photos

Scene type distribution:
├─ Landscape: 11 photos (37%)
├─ People: 9 photos (30%)
├─ City: 5 photos (17%)
├─ Food: 3 photos (10%)
└─ Misc: 2 photos (6%)

Time of day distribution:
├─ Day: 18 photos (60%)
├─ Golden hour: 8 photos (27%)
└─ Night: 4 photos (13%)

Average aesthetic score: 0.72
Average blur score: 0.21
```

---

## Debugging & Tuning

### View Individual Photo Scores

```kotlin
fun explainPhotoScore(photo: Photo, context: ScoringContext): String {
    val score = scorePhoto(photo, context.weights, context.sceneCounts, context.timeCounts)

    return """
    Photo: ${photo.originalFilename}
    Total Score: ${score.totalScore.format(3)}

    Breakdown:
    ├─ Aesthetic: ${score.breakdown.aestheticScore.format(3)}
    │  (${photo.signals?.aestheticScore} × ${context.weights.aestheticWeight})
    ├─ Sharpness: ${score.breakdown.sharpnessScore.format(3)}
    │  (${1.0 - (photo.signals?.blurScore ?: 0.0)} × ${context.weights.sharpnessWeight})
    ├─ Scene Variety: ${score.breakdown.sceneVarietyScore.format(3)}
    │  (${photo.signals?.sceneType}, count: ${context.sceneCounts[photo.signals?.sceneType]})
    ├─ Time Variety: ${score.breakdown.timeVarietyScore.format(3)}
    │  (${photo.signals?.timeOfDay}, count: ${context.timeCounts[photo.signals?.timeOfDay]})
    ├─ Orientation: ${score.breakdown.orientationScore.format(3)}
    │  (${Orientation.from(photo.metadata.width, photo.metadata.height)})
    └─ Lighting: ${score.breakdown.lightingScore.format(3)}
       (${photo.signals?.timeOfDay})
    """.trimIndent()
}
```

### Tuning Parameters

**If book has too many landscapes:**
```kotlin
DiversityConfig(
    sceneTypeTargets = mapOf(
        "people" to 0.40,      // Increase people
        "landscape" to 0.25,   // Decrease landscape
        // ...
    )
)
```

**If photos are too conservative (all safe picks):**
```kotlin
ScoringWeights(
    aestheticWeight = 0.40,       // Reduce aesthetic importance
    sceneVarietyBonus = 0.15      // Increase variety importance
)
```

**If too many blurry photos getting through:**
```kotlin
QualityThresholds(
    maxBlurScore = 0.4  // Stricter blur threshold
)
```

---

## Testing Strategy

### Unit Tests

```kotlin
@Test
fun `should filter out blurry photos`() {
    val photos = listOf(
        photoWithBlur(0.2),  // Sharp
        photoWithBlur(0.5),  // Acceptable
        photoWithBlur(0.8)   // Blurry
    )

    val filtered = filterLowQuality(photos, QualityThresholds(maxBlurScore = 0.6))

    assertEquals(2, filtered.size)
    assertTrue(filtered.all { it.signals!!.blurScore <= 0.6 })
}

@Test
fun `should detect bursts correctly`() {
    val photos = listOf(
        photoAt(time = "10:00:00"),
        photoAt(time = "10:00:03"),
        photoAt(time = "10:00:07"),
        photoAt(time = "10:05:00")
    )

    val bursts = detectBursts(photos, BurstConfig(timeWindowSeconds = 10))

    assertEquals(1, bursts.size)
    assertEquals(3, bursts[0].photos.size)
}

@Test
fun `should prefer landscape orientation`() {
    val landscape = photoWithOrientation(width = 4000, height = 3000)
    val portrait = photoWithOrientation(width = 3000, height = 4000)

    val landscapeScore = scorePhoto(landscape, ScoringWeights(), emptyMap(), emptyMap())
    val portraitScore = scorePhoto(portrait, ScoringWeights(), emptyMap(), emptyMap())

    assertTrue(landscapeScore.totalScore > portraitScore.totalScore)
}

@Test
fun `should cap scene types at target`() {
    val photos = (1..50).map { photoWithScene("landscape") } +
                 (1..10).map { photoWithScene("people") }

    val selected = selectPhotos(
        photos,
        ScoringWeights(),
        DiversityConfig(
            targetPhotos = 30,
            sceneTypeTargets = mapOf(
                "landscape" to 0.50,  // Max 15 photos
                "people" to 0.50      // Max 15 photos
            )
        )
    )

    val landscapeCount = selected.count { it.signals?.sceneType == "landscape" }
    assertEquals(15, landscapeCount)
}
```

---

## Summary

| Phase | Purpose | Result |
|-------|---------|--------|
| 1. Quality Filter | Remove unusable photos | -30% photos |
| 2. Burst Detection | Remove duplicates | -20% photos |
| 3. Weighted Scoring | Rank by quality + preferences | Ranked list |
| 4. Diversity Selection | Balance variety | Final 30 photos |

**Key Properties:**
- ✅ Deterministic (same input → same output)
- ✅ Explainable (can see why each photo scored X)
- ✅ Configurable (no magic numbers)
- ✅ Debuggable (logs and breakdowns available)
- ✅ Testable (pure functions, no side effects)

This algorithm ensures high-quality, diverse photobooks while remaining transparent and tunable.

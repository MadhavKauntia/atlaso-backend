# Photo Selection Algorithm - Quick Reference

## Overview

**Purpose:** Select the best 30 photos for a travel photobook from 50-200+ trip photos.

**Key Properties:**
- ✅ Deterministic (same input → same output)
- ✅ Explainable (can show why each photo scored X)
- ✅ Configurable (no magic numbers)
- ✅ Testable (pure functions)

---

## Algorithm Phases

```
100 Photos
    ↓
Phase 1: Quality Filter → 70 Photos
    ↓
Phase 2: Burst Detection → 45 Photos
    ↓
Phase 3: Weighted Scoring → Ranked List
    ↓
Phase 4: Diversity Selection → 30 Photos
```

---

## Scoring Formula

**Total Score = Sum of weighted components (0.0 - 1.0):**

```
Score = (Aesthetic × 0.50)
      + (Sharpness × 0.20)
      + (Scene Variety × 0.10)
      + (Time Variety × 0.05)
      + (Orientation × 0.10)
      + (Lighting × 0.05)
```

### Component Details

| Component | Weight | What It Measures | Range |
|-----------|--------|------------------|-------|
| Aesthetic | 50% | Photo quality (AI analysis) | 0.0 - 1.0 |
| Sharpness | 20% | 1.0 - blur_score | 0.0 - 1.0 |
| Scene Variety | 10% | Bonus for underrepresented scenes | 0.0 - 1.0 |
| Time Variety | 5% | Bonus for underrepresented times | 0.0 - 1.0 |
| Orientation | 10% | Landscape=1.0, Portrait=0.7, Square=0.5 | 0.5 - 1.0 |
| Lighting | 5% | Golden=1.0, Day=0.8, Night=0.6 | 0.6 - 1.0 |

---

## Configuration

### Quality Thresholds
```kotlin
QualityThresholds(
    minAestheticScore = 0.4,   // Below this = reject
    maxBlurScore = 0.6,        // Above this = reject
    minDimension = 1200        // Below this = reject (px)
)
```

**Rationale:**
- `0.4` aesthetic: Removes bottom 40% (poor composition/lighting)
- `0.6` blur: Noticeable but not unusable
- `1200px`: Ensures print quality

### Burst Detection
```kotlin
BurstConfig(
    timeWindowSeconds = 10     // Photos <10s apart = burst
)
```

**Behavior:**
- Groups photos taken within 10 seconds
- Keeps only the highest aesthetic score from each burst

### Diversity Targets
```kotlin
DiversityConfig(
    targetPhotos = 30,
    sceneTypeTargets = mapOf(
        "people" to 0.30,      // ~9 photos
        "landscape" to 0.35,   // ~10-11 photos
        "city" to 0.15,        // ~4-5 photos
        "food" to 0.10,        // ~3 photos
        "misc" to 0.10         // ~3 photos
    )
)
```

---

## Example Score Calculation

### Photo: Beach Sunset
```
Input Data:
  aesthetic_score: 0.85
  blur_score: 0.10
  scene_type: landscape (2 already selected)
  time_of_day: golden_hour (1 already selected)
  dimensions: 4032 × 3024 (landscape)

Calculation:
  Aesthetic:      0.85 × 0.50 = 0.425
  Sharpness:      0.90 × 0.20 = 0.180
  Scene Variety:  0.90 × 0.10 = 0.090  (2 landscapes → bonus=0.9)
  Time Variety:   0.90 × 0.05 = 0.045  (1 golden hour → bonus=0.9)
  Orientation:    1.00 × 0.10 = 0.100  (landscape)
  Lighting:       1.00 × 0.05 = 0.050  (golden_hour)
  ────────────────────────────────────
  Total Score:                  0.890
```

**Assessment:** Excellent photo, very likely to be selected.

---

## Debugging Commands

### Explain Individual Photo Score
```kotlin
val explainer = PhotoScoreExplainer()
val explanation = explainer.explainScore(photo, photoScore, weights, rank = 5)
println(explanation)
```

**Output:**
```
═══════════════════════════════════════════════════
Photo: IMG_1234.jpg
Rank: #5
Total Score: 0.890 / 1.000
═══════════════════════════════════════════════════

SCORE BREAKDOWN:

1. Aesthetic Quality
   Raw Score:    0.850
   Weight:       0.500 (50%)
   Contribution: 0.425
   └─ Good - Well-composed

2. Sharpness
   Blur Score:   0.100
   Sharp Score:  0.900
   Weight:       0.200 (20%)
   Contribution: 0.180
   └─ Sharp - Excellent detail

...
```

### Compare Two Photos
```kotlin
val comparison = explainer.comparePhotos(photo1, score1, photo2, score2)
println(comparison)
```

### Check Why Photo Was Rejected
```kotlin
val rejection = explainer.explainRejection(photo, thresholds)
println(rejection)
```

**Output:**
```
❌ REJECTED:
  • Blur score 0.75 > threshold 0.6
  • Aesthetic score 0.35 < threshold 0.4
```

---

## Tuning Guide

### Problem: Too Many Landscapes

**Solution:** Adjust diversity targets
```kotlin
DiversityConfig(
    sceneTypeTargets = mapOf(
        "people" to 0.40,      // Increase
        "landscape" to 0.25,   // Decrease
        ...
    )
)
```

### Problem: Too Conservative (All Safe Photos)

**Solution:** Reduce aesthetic weight, increase variety
```kotlin
ScoringWeights(
    aestheticWeight = 0.40,       // From 0.50
    sceneVarietyBonus = 0.15,     // From 0.10
    timeVarietyBonus = 0.10       // From 0.05
)
```

### Problem: Blurry Photos Getting Through

**Solution:** Stricter blur threshold
```kotlin
QualityThresholds(
    maxBlurScore = 0.4            // From 0.6
)
```

### Problem: Missing Night Photos

**Solution:** Adjust time diversity targets
```kotlin
DiversityConfig(
    timeTargets = mapOf(
        "day" to 0.50,            // From 0.60
        "night" to 0.25           // From 0.15
    )
)
```

---

## Variety Bonus Function

**Purpose:** Encourages diversity by boosting underrepresented categories.

```kotlin
fun calculateVarietyBonus(count: Int): Double {
    return when {
        count == 0 -> 1.0      // Max bonus
        count <= 2 -> 0.9      // High bonus
        count <= 5 -> 0.7      // Medium bonus
        count <= 10 -> 0.4     // Low bonus
        else -> 0.0            // No bonus
    }
}
```

**Example:**
```
Landscape photos selected: 0  → Variety bonus: 1.0  (select more!)
Landscape photos selected: 2  → Variety bonus: 0.9  (still good)
Landscape photos selected: 5  → Variety bonus: 0.7  (getting many)
Landscape photos selected: 10 → Variety bonus: 0.4  (have enough)
Landscape photos selected: 15 → Variety bonus: 0.0  (quota reached)
```

---

## Testing

### Run Unit Tests
```bash
./gradlew test --tests PhotoSelectorTest
```

### Key Test Cases
- ✅ Quality filtering (blur, aesthetic, dimensions)
- ✅ Burst detection (time-based grouping)
- ✅ Orientation preference (landscape > portrait)
- ✅ Lighting preference (golden_hour > day > night)
- ✅ Scene diversity (caps at targets)
- ✅ Chronological ordering (output sorted by time)

---

## Performance

### Complexity

| Phase | Complexity | Notes |
|-------|-----------|-------|
| Quality Filter | O(n) | Single pass |
| Burst Detection | O(n log n) | Sort + scan |
| Scoring | O(n) | Score each photo |
| Selection | O(n² log n) | Re-score after each selection |

**For 100 photos:** ~50ms (fast enough for synchronous API)

### Optimization (Future)

If processing becomes slow with 1000+ photos:
1. **Batch Burst Detection:** Process in chunks
2. **Cache Scores:** Don't re-score if counts unchanged
3. **Parallel Scoring:** Use coroutines for scoring phase

---

## API Usage

```kotlin
@Service
class BookGenerationService(
    private val photoSelector: PhotoSelector
) {
    fun generateBook(tripId: UUID): Book {
        // 1. Fetch photos
        val photos = photoRepository.findByTripId(tripId)

        // 2. Select best photos
        val result = photoSelector.selectPhotosForBook(photos)

        // 3. Log selection stats
        logger.info("Selected ${result.photos.size} photos")
        logger.info("Scene distribution: ${result.stats.sceneTypeDistribution}")
        logger.info("Avg aesthetic: ${result.stats.averageAestheticScore}")

        // 4. Create book with selected photos
        val book = createBookFromPhotos(result.photos)

        return book
    }
}
```

---

## Example Selection Log

```
Starting with 127 photos
After quality filter: 89 photos (removed 38)
After burst deduplication: 58 photos (removed 31)
Final selection: 30 photos

Scene type distribution:
├─ landscape: 11 photos (37%)
├─ people: 9 photos (30%)
├─ city: 5 photos (17%)
├─ food: 3 photos (10%)
└─ misc: 2 photos (7%)

Time of day distribution:
├─ day: 18 photos (60%)
├─ golden_hour: 8 photos (27%)
└─ night: 4 photos (13%)

Average aesthetic score: 0.74
Average blur score: 0.18
```

---

## Key Design Decisions

### Why Variety Bonuses?

Without variety bonuses, top 30 photos might all be:
- Landscapes (if user took many beautiful sunset shots)
- Daytime (if trip was mostly during day)

Variety bonuses ensure a **balanced, interesting photobook**.

### Why Chronological Output?

Books tell a story. Sorting by capture time creates a natural narrative flow:
```
Day 1: Arrival → Lunch → Beach
Day 2: Sunrise → City → Dinner
Day 3: Museum → Food Market → Sunset
```

### Why Cap Scene Types?

Prevents:
- All landscape photobook (boring)
- All people photobook (lacks context)
- All food photobook (one-dimensional)

Ensures variety while still prioritizing quality.

### Why Landscape Preference?

- Full-page layouts work better with landscape
- More dramatic impact
- Better use of book real estate

But: We still include portraits for variety (0.7 score vs 1.0).

---

## Summary

This algorithm balances three goals:

1. **Quality:** Select high aesthetic, sharp photos
2. **Preference:** Favor landscapes and golden hour
3. **Diversity:** Ensure variety in scenes and lighting

The result: **High-quality, diverse photobooks that tell a complete story.**

All decisions are transparent, configurable, and debuggable.

# Atlaso Backend - Entity Design Documentation

## Overview

This document explains the design decisions for the JPA entities in the Atlaso photobook generation system.

---

## Entity Structure

### 1. Trip Entity
**Location:** `com.atlaso.domain.trip.Trip`

```kotlin
@Entity
@Table(name = "trips")
data class Trip(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    var name: String,
    var destination: String? = null,
    var startDate: LocalDate? = null,
    var endDate: LocalDate? = null,
    var status: TripStatus = TripStatus.CREATED,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
```

**Key Design Decisions:**

- **UUID Primary Key**: Using `GenerationType.UUID` for globally unique, non-sequential IDs
- **Nullable Fields**: `destination`, `startDate`, `endDate` are optional for v1 flexibility
- **Status Enum**: Stored as `STRING` in DB for readability (vs. `ORDINAL`)
- **Automatic Timestamps**: `@CreationTimestamp` and `@UpdateTimestamp` handle audit fields
- **Mutable State**: `var` for fields that change during lifecycle (name, status)

---

### 2. Photo Entity
**Location:** `com.atlaso.domain.photo.Photo`

```kotlin
@Entity
@Table(name = "photos")
data class Photo(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    val trip: Trip,

    val storageKey: String,
    val originalFilename: String,
    val contentType: String,
    val fileSize: Long,

    @Type(JsonType::class)
    @Column(columnDefinition = "jsonb")
    val metadata: PhotoMetadata,

    @Type(JsonType::class)
    @Column(columnDefinition = "jsonb")
    var signals: PhotoSignals? = null,

    val uploadedAt: Instant? = null,
    var analyzedAt: Instant? = null
)
```

**Key Design Decisions:**

- **JSONB Storage**: Using PostgreSQL's `jsonb` for flexible, queryable JSON data
  - `metadata`: EXIF data (width, height, GPS, camera info)
  - `signals`: AI analysis results (aesthetic score, scene type, faces)
- **Hypersistence Utils**: Using `@Type(JsonType::class)` for seamless JSON mapping
- **Lazy Loading**: `FetchType.LAZY` on trip relationship to avoid N+1 queries
- **Nullable Signals**: Photos start without AI analysis; signals populated async
- **Unique Storage Key**: Prevents duplicate uploads
- **Immutable Core Data**: Storage info is `val` (never changes after upload)

**PhotoMetadata Structure:**
```kotlin
data class PhotoMetadata(
    val width: Int,
    val height: Int,
    val takenAt: Instant? = null,
    val location: GeoLocation? = null,
    val orientation: Int = 1,
    val cameraModel: String? = null
) : Serializable
```

**PhotoSignals Structure:**
```kotlin
data class PhotoSignals(
    val sceneType: String? = null,
    val aestheticScore: Double = 0.0,
    val facesCount: Int = 0,
    val detectedObjects: List<String> = emptyList(),
    val isBlurry: Boolean = false,
    val dominantColors: List<String> = emptyList()
) : Serializable
```

---

### 3. Book Entity
**Location:** `com.atlaso.domain.book.Book`

```kotlin
@Entity
@Table(name = "books")
data class Book(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    val trip: Trip,

    val version: Int = 1,
    var title: String,
    var subtitle: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_photo_id")
    var coverPhoto: Photo? = null,

    @OneToMany(mappedBy = "book", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("pageNumber ASC")
    val pages: MutableList<Page> = mutableListOf(),

    var status: BookStatus = BookStatus.GENERATING,
    val generatedAt: Instant? = null,
    var pdfUrl: String? = null
)
```

**Key Design Decisions:**

- **Version Field**: Enables multiple book versions from same trip (regeneration)
- **Bidirectional Relationship**: Book ↔ Pages with cascade operations
  - `CascadeType.ALL`: Deleting book deletes all pages
  - `orphanRemoval = true`: Removing page from list deletes it from DB
- **OrderBy**: Pages automatically sorted by page number
- **Helper Methods**: `addPage()` and `removePage()` maintain both sides of relationship
- **Cover Photo Reference**: Optional reference to highlight photo
- **Mutable State**: Title, subtitle, coverPhoto, status, pdfUrl can change

**Relationship Management:**
```kotlin
fun addPage(page: Page) {
    pages.add(page)
    page.book = this  // Maintain bidirectional consistency
}
```

---

### 4. Page Entity
**Location:** `com.atlaso.domain.book.Page`

```kotlin
@Entity
@Table(name = "pages")
data class Page(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    var book: Book? = null,

    val pageNumber: Int,
    val layout: Layout,

    @Type(JsonType::class)
    @Column(columnDefinition = "jsonb")
    val slots: List<PhotoSlot> = emptyList()
)
```

**Key Design Decisions:**

- **JSONB for PhotoSlots**: Simplifies v1 implementation vs. complex join tables
- **PhotoSlot Structure**: Stores photo ID + position + size + caption
- **Layout Enum**: Predefined layouts (SINGLE_FULL, TWO_HORIZONTAL, FOUR_GRID, etc.)
- **Immutable Once Created**: Page structure doesn't change after generation
- **Nullable Book Reference**: Required for bidirectional relationship management

**PhotoSlot Structure:**
```kotlin
data class PhotoSlot(
    val photoId: UUID,          // Reference to photo
    val position: Position,     // (x, y) as 0.0-1.0 percentages
    val size: Size,             // (width, height) as percentages
    val caption: String? = null
) : Serializable
```

**Position & Size:**
```kotlin
data class Position(val x: Double, val y: Double) : Serializable
data class Size(val width: Double, val height: Double) : Serializable
```

- **Relative Coordinates**: 0.0 to 1.0 (percentage-based for responsive rendering)
- **Flexible Layout**: Can position photos anywhere on the page

---

## Database Schema

**Created via Flyway migration:** `V1__initial_schema.sql`

Key database features:
- **CASCADE Deletes**: Deleting trip removes photos, books, and pages
- **GIN Index on signals**: Fast queries on AI analysis JSON data
- **Composite Index**: `(trip_id, version)` for efficient book version lookups
- **Foreign Keys**: Maintain referential integrity

---

## Key Architecture Patterns

### 1. JSON Storage for Flexibility

**Why JSONB?**
- Avoids complex join tables for nested data
- Queryable with PostgreSQL JSON operators
- Schema flexibility for AI signals evolution
- Simpler v1 implementation

**Tradeoffs:**
- Can't enforce schema at DB level
- Slightly harder to query compared to normalized tables
- ✅ Acceptable for v1: PhotoSlots and signals are read-heavy, not query targets

### 2. UUID Primary Keys

**Benefits:**
- Globally unique (safe for distributed systems)
- Non-sequential (no info leakage)
- Generate client-side if needed

**Tradeoffs:**
- Larger than `BIGINT` (128 bits vs 64 bits)
- ✅ Acceptable: Storage is cheap, uniqueness is valuable

### 3. Lazy Loading

**Pattern:**
```kotlin
@ManyToOne(fetch = FetchType.LAZY)
val trip: Trip
```

**Benefits:**
- Prevents N+1 queries
- Load related entities only when accessed
- Better performance for list operations

**Important:** Use `@EntityGraph` or JOIN FETCH in repositories when you need relations

### 4. Enum as STRING

**Pattern:**
```kotlin
@Enumerated(EnumType.STRING)
val status: TripStatus
```

**Benefits:**
- DB values are readable ("READY_FOR_PREVIEW" vs "2")
- Safe to reorder enum constants
- Easier debugging

**Tradeoffs:**
- Slightly more storage than `ORDINAL`
- ✅ Worth it for maintainability

### 5. Immutable by Default

**Pattern:**
```kotlin
val id: UUID?              // Immutable
var status: BookStatus     // Mutable state
```

**Philosophy:**
- Use `val` for data that never changes (IDs, timestamps, metadata)
- Use `var` only for legitimate state transitions (status, title, signals)
- Kotlin's data classes + JPA work well together

---

## JSON Column Examples

### Querying PhotoSignals in PostgreSQL

```sql
-- Find photos with high aesthetic score
SELECT * FROM photos
WHERE (signals->>'aestheticScore')::float > 0.8;

-- Find photos with faces
SELECT * FROM photos
WHERE (signals->>'facesCount')::int > 0;

-- Find beach photos
SELECT * FROM photos
WHERE signals->>'sceneType' = 'beach';

-- GIN index makes JSON queries fast
CREATE INDEX idx_photos_signals ON photos USING GIN (signals);
```

### Querying PhotoSlots in PostgreSQL

```sql
-- Find pages with more than 2 photos
SELECT * FROM pages
WHERE jsonb_array_length(slots) > 2;

-- Find pages containing a specific photo
SELECT * FROM pages
WHERE slots @> '[{"photoId": "123e4567-e89b-12d3-a456-426614174000"}]'::jsonb;
```

---

## Entity Lifecycle Examples

### 1. Creating a Trip with Photos

```kotlin
// Create trip
val trip = Trip(
    name = "Summer in Greece",
    destination = "Santorini",
    startDate = LocalDate.of(2024, 7, 1),
    endDate = LocalDate.of(2024, 7, 10),
    status = TripStatus.CREATED
)
tripRepository.save(trip)

// Upload photos
val photo = Photo(
    trip = trip,
    storageKey = "trips/123/photo1.jpg",
    originalFilename = "IMG_1234.jpg",
    contentType = "image/jpeg",
    fileSize = 2_500_000,
    metadata = PhotoMetadata(
        width = 4032,
        height = 3024,
        takenAt = Instant.now(),
        orientation = 1
    )
)
photoRepository.save(photo)

// Later: AI analysis
photo.signals = PhotoSignals(
    sceneType = "beach",
    aestheticScore = 0.85,
    facesCount = 2,
    detectedObjects = listOf("person", "ocean", "sunset"),
    isBlurry = false,
    dominantColors = listOf("#FF6B35", "#004E89")
)
photo.analyzedAt = Instant.now()
photoRepository.save(photo)
```

### 2. Generating a Book

```kotlin
// Create book
val book = Book(
    trip = trip,
    version = 1,
    title = "Summer in Greece",
    subtitle = "July 2024",
    status = BookStatus.GENERATING
)

// Add pages with photos
val page1 = Page(
    pageNumber = 1,
    layout = Layout.SINGLE_FULL,
    slots = listOf(
        PhotoSlot(
            photoId = coverPhoto.id!!,
            position = Position(0.0, 0.0),
            size = Size(1.0, 1.0),
            caption = "Sunset in Santorini"
        )
    )
)

book.addPage(page1)
book.status = BookStatus.READY_FOR_PREVIEW
bookRepository.save(book)
```

### 3. Regenerating a Book

```kotlin
// Create new version
val newBook = Book(
    trip = existingBook.trip,
    version = existingBook.version + 1,
    title = existingBook.title,
    status = BookStatus.GENERATING
)

// Layout engine creates new page arrangement
// (same photos, different layout algorithm result)
```

---

## Testing Considerations

### 1. Entity Tests
```kotlin
@DataJpaTest
class TripRepositoryTest {
    @Test
    fun `should save trip with all fields`() {
        val trip = Trip(
            name = "Test Trip",
            destination = "Paris",
            status = TripStatus.CREATED
        )
        val saved = tripRepository.save(trip)

        assertNotNull(saved.id)
        assertNotNull(saved.createdAt)
        assertEquals("Test Trip", saved.name)
    }
}
```

### 2. JSON Serialization Tests
```kotlin
@Test
fun `should serialize and deserialize PhotoSignals`() {
    val signals = PhotoSignals(
        sceneType = "mountain",
        aestheticScore = 0.9,
        facesCount = 1
    )

    val json = objectMapper.writeValueAsString(signals)
    val deserialized = objectMapper.readValue<PhotoSignals>(json)

    assertEquals(signals, deserialized)
}
```

---

## Migration Strategy

**Current:** Flyway for version-controlled schema changes

**Future Migrations Example:**
```sql
-- V2__add_book_template.sql
ALTER TABLE books ADD COLUMN template_id UUID REFERENCES templates(id);

-- V3__add_photo_tags.sql
ALTER TABLE photos ADD COLUMN tags TEXT[];
CREATE INDEX idx_photos_tags ON photos USING GIN (tags);
```

---

## Performance Considerations

### 1. N+1 Query Prevention

**Bad:**
```kotlin
val books = bookRepository.findAll()  // 1 query
books.forEach { book ->
    book.pages.forEach { page ->       // N queries
        println(page.layout)
    }
}
```

**Good:**
```kotlin
@Query("SELECT b FROM Book b LEFT JOIN FETCH b.pages WHERE b.id = :id")
fun findByIdWithPages(id: UUID): Book?
```

### 2. JSON Indexing

**Enable fast queries on AI signals:**
```sql
CREATE INDEX idx_photos_signals ON photos USING GIN (signals);
```

### 3. Pagination

**For large photo collections:**
```kotlin
fun findByTripId(tripId: UUID, pageable: Pageable): Page<Photo>
```

---

## Summary

| Decision | Rationale | Tradeoff |
|----------|-----------|----------|
| UUID IDs | Global uniqueness, non-sequential | Larger than BIGINT |
| JSONB for signals | Flexible schema, queryable | Lacks DB-level validation |
| JSONB for slots | Simpler v1 implementation | Harder to query than joins |
| Enum as STRING | Readable, safe to reorder | More storage |
| Lazy loading | Prevents N+1 queries | Must be explicit when needed |
| Flyway migrations | Version control for schema | Requires discipline |
| Bidirectional relationships | Convenient navigation | Must maintain consistency |
| Cascading deletes | Automatic cleanup | Must understand implications |

---

## Next Steps

1. **Create Repositories**: Spring Data JPA interfaces
2. **Add Entity Graphs**: Optimize query performance
3. **Write Integration Tests**: Verify relationships and JSON storage
4. **Add Auditing**: Track who/when for changes (future)
5. **Add Soft Deletes**: If needed for user data retention (future)

This entity design provides a solid, clean foundation for the Atlaso v1 backend while maintaining flexibility for future enhancements.

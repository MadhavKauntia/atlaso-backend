package com.atlaso.domain.book

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.trip.Trip
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "books")
data class Book(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    val trip: Trip,

    @Column(nullable = false)
    val version: Int = 1,

    @Column(nullable = false, length = 255)
    var title: String,

    @Column(length = 255)
    var subtitle: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_photo_id")
    var coverPhoto: Photo? = null,

    @OneToMany(
        mappedBy = "book",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @OrderBy("pageNumber ASC")
    val pages: MutableList<Page> = mutableListOf(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var status: BookStatus = BookStatus.GENERATING,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "generated_at")
    val generatedAt: Instant? = null,

    @Column(name = "pdf_url", length = 1024)
    var pdfUrl: String? = null,

    @Column(name = "cover_template_id", length = 64)
    var coverTemplateId: String? = null,

    @Column(name = "cover_palette_id", length = 64)
    var coverPaletteId: String? = null
) {
    fun addPage(page: Page) {
        pages.add(page)
        page.book = this
    }

    fun removePage(page: Page) {
        pages.remove(page)
        page.book = null
    }
}

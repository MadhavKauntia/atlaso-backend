package com.atlaso.domain.book

import io.hypersistence.utils.hibernate.type.json.JsonType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.util.UUID

@Entity
@Table(name = "pages")
data class Page(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    var book: Book? = null,

    @Column(nullable = false, name = "page_number")
    val pageNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    val layout: Layout,

    @Type(JsonType::class)
    @Column(nullable = false, columnDefinition = "jsonb")
    val slots: List<PhotoSlot> = emptyList()
)

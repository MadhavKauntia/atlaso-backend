package com.atlaso.service

import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * The print PDF is ONE file: front cover, interior pages, back cover. Pages are identified by the
 * pixel width of the image drawn on them (front = 141, back = 142, interior pages = their own widths),
 * so these tests check the real page order, not just the count.
 */
class PrintPdfTest {

    private val renderer = PdfRenderer()

    private fun png(width: Int, height: Int = 200, color: Color = Color.GREEN): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { this.color = color; fillRect(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private val front = png(141)
    private val back = png(142)

    /** Interior page whose single slot holds an image [width] px wide (identifies the page). */
    private fun interior(pageNumber: Int, width: Int) =
        PageRenderData(pageNumber, listOf(SlotRenderData(0.0, 0.0, 1.0, 1.0, imageBytes = png(width, 100), caption = null)))

    /** Image widths per page, in page order (null for a page with no image, e.g. a text cover). */
    private fun imageWidths(pdf: ByteArray): List<Int?> = Loader.loadPDF(pdf).use { doc ->
        doc.pages.map { page ->
            val res = page.resources
            res.xObjectNames.mapNotNull { res.getXObject(it) as? PDImageXObject }.firstOrNull()?.width
        }
    }

    private fun pageSizes(pdf: ByteArray) = Loader.loadPDF(pdf).use { d -> d.pages.map { it.mediaBox.width to it.mediaBox.height }.toSet() }

    @Test
    fun `print book is front cover, then interior pages in order, then back cover`() {
        val pdf = renderer.renderPrintBook("Bali", "2025", listOf(interior(1, 61), interior(2, 62), interior(3, 63)), front, back)
        assertEquals(listOf(141, 61, 62, 63, 142), imageWidths(pdf))
        assertEquals(setOf(PdfRenderer.PAGE_WIDTH to PdfRenderer.PAGE_HEIGHT), pageSizes(pdf)) // every page at trim size
    }

    @Test
    fun `without a client cover the first page is a text cover and there is no back page`() {
        val pdf = renderer.renderPrintBook("Bali", null, listOf(interior(1, 61), interior(2, 62)), frontCoverPng = null, backCoverPng = back)
        assertEquals(listOf(null, 61, 62), imageWidths(pdf))
    }

    @Test
    fun `a front cover without a back image has no back page`() {
        val pdf = renderer.renderPrintBook("Bali", null, listOf(interior(1, 61)), front, backCoverPng = null)
        assertEquals(listOf(141, 61), imageWidths(pdf))
    }

    // ---- legacy two-file books ----

    private fun imagePdf(vararg widths: Int): ByteArray = PDDocument().use { doc ->
        widths.forEach { w ->
            val page = PDPage(PDRectangle(PdfRenderer.PAGE_WIDTH, PdfRenderer.PAGE_HEIGHT))
            doc.addPage(page)
            val img = PDImageXObject.createFromByteArray(doc, png(w), "img$w")
            PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, PdfRenderer.PAGE_WIDTH, PdfRenderer.PAGE_HEIGHT) }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `legacy cover and interior merge into front, interior, back`() {
        val merged = PrintPdfStore.mergeLegacy(coverPdf = imagePdf(141, 142), interiorPdf = imagePdf(61, 62, 63))
        assertEquals(listOf(141, 61, 62, 63, 142), imageWidths(merged))
    }

    @Test
    fun `legacy single-page text cover merges with no back page`() {
        val merged = PrintPdfStore.mergeLegacy(coverPdf = imagePdf(141), interiorPdf = imagePdf(61, 62))
        assertEquals(listOf(141, 61, 62), imageWidths(merged))
    }

    // ---- where load() reads from ----

    private val storage = mock<StorageService>()
    private val store = PrintPdfStore(storage)
    private val trip = Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION).copy(id = UUID.randomUUID())
    private fun book(pdfUrl: String?): Book {
        val id = UUID.randomUUID()
        return Book(id = id, trip = trip, version = 1, title = "Bali", status = BookStatus.PDF_READY).also {
            it.pdfUrl = pdfUrl?.replace("{id}", id.toString())
        }
    }

    @Test
    fun `a single-file book is served as stored`() {
        val b = book("pdfs/{id}/book.pdf")
        val bytes = imagePdf(141, 61, 142)
        whenever(storage.load(PrintPdfStore.key(b.id!!))).thenReturn(bytes)
        assertArrayEquals(bytes, store.load(b))
        verify(storage, never()).load(PrintPdfStore.legacyCoverKey(b.id!!))
    }

    @Test
    fun `a legacy book is merged on the fly into one file`() {
        val b = book("pdfs/{id}/photobook.pdf")
        whenever(storage.load(PrintPdfStore.legacyCoverKey(b.id!!))).thenReturn(imagePdf(141, 142))
        whenever(storage.load(PrintPdfStore.legacyInteriorKey(b.id!!))).thenReturn(imagePdf(61, 62))
        assertEquals(listOf(141, 61, 62, 142), imageWidths(store.load(b)))
    }

    @Test
    fun `a book with no PDF can't be loaded`() {
        assertThrows(IllegalStateException::class.java) { store.load(book(null)) }
    }
}

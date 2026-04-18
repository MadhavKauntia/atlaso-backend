package com.atlaso.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class SlotRenderData(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val imageBytes: ByteArray?,
    val caption: String?,
    val rotation: Int = 0,
    val offsetX: Double = 0.5,
    val offsetY: Double = 0.5
)

data class PageRenderData(
    val pageNumber: Int,
    val slots: List<SlotRenderData>
)

@Component
class PdfRenderer {

    private val logger = LoggerFactory.getLogger(PdfRenderer::class.java)

    companion object {
        const val PAGE_WIDTH = 595f  // A4 portrait width in points
        const val PAGE_HEIGHT = 842f // A4 portrait height in points
        private const val MARGIN = 20f
        private const val CAPTION_FONT_SIZE = 8f
        private const val TITLE_FONT_SIZE = 32f
        private const val SUBTITLE_FONT_SIZE = 18f
        private const val MAX_IMAGE_DIMENSION = 1800  // ~150 DPI on A4, sufficient for print
    }

    fun render(
        title: String,
        subtitle: String?,
        pages: List<PageRenderData>
    ): ByteArray {
        val document = PDDocument()
        try {
            renderCoverPage(document, title, subtitle)

            for (page in pages) {
                renderContentPage(document, page)
            }

            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun renderCoverPage(
        document: PDDocument,
        title: String,
        subtitle: String?
    ) {
        val pageSize = PDRectangle(PAGE_WIDTH, PAGE_HEIGHT)
        val page = PDPage(pageSize)
        document.addPage(page)

        PDPageContentStream(document, page).use { cs ->
            // Dark background
            cs.setNonStrokingColor(0.12f, 0.12f, 0.14f)
            cs.addRect(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
            cs.fill()

            // Title text
            val helveticaBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

            cs.setNonStrokingColor(1f, 1f, 1f)

            val titleWidth = helveticaBold.getStringWidth(title) / 1000f * TITLE_FONT_SIZE
            val titleX = (PAGE_WIDTH - titleWidth) / 2f
            val titleY = if (subtitle != null) PAGE_HEIGHT / 2f + 10f else PAGE_HEIGHT / 2f - 10f

            cs.beginText()
            cs.setFont(helveticaBold, TITLE_FONT_SIZE)
            cs.newLineAtOffset(titleX, titleY)
            cs.showText(title)
            cs.endText()

            // Subtitle text
            if (subtitle != null) {
                val subtitleWidth = helvetica.getStringWidth(subtitle) / 1000f * SUBTITLE_FONT_SIZE
                val subtitleX = (PAGE_WIDTH - subtitleWidth) / 2f
                val subtitleY = titleY - 35f

                cs.beginText()
                cs.setFont(helvetica, SUBTITLE_FONT_SIZE)
                cs.newLineAtOffset(subtitleX, subtitleY)
                cs.showText(subtitle)
                cs.endText()
            }
        }
    }

    private fun renderContentPage(document: PDDocument, pageData: PageRenderData) {
        val pageSize = PDRectangle(PAGE_WIDTH, PAGE_HEIGHT)
        val page = PDPage(pageSize)
        document.addPage(page)

        PDPageContentStream(document, page).use { cs ->
            // White background
            cs.setNonStrokingColor(1f, 1f, 1f)
            cs.addRect(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
            cs.fill()

            for (slot in pageData.slots) {
                renderSlot(cs, document, slot)
            }
        }
    }

    private fun renderSlot(cs: PDPageContentStream, document: PDDocument, slot: SlotRenderData) {
        // Convert from top-down coordinates (0..1 fractional) to PDF bottom-up points
        val slotX = (slot.x * PAGE_WIDTH).toFloat()
        val slotW = (slot.width * PAGE_WIDTH).toFloat()
        val slotH = (slot.height * PAGE_HEIGHT).toFloat()
        // PDF origin is bottom-left; slot.y is from top
        val slotY = PAGE_HEIGHT - (slot.y * PAGE_HEIGHT).toFloat() - slotH

        if (slot.imageBytes != null) {
            try {
                val image = loadImage(document, slot.imageBytes, slot.rotation)
                drawCoverFitImage(cs, image, slotX, slotY, slotW, slotH, slot.offsetX.toFloat(), slot.offsetY.toFloat())
            } catch (e: Exception) {
                logger.warn("Failed to render photo in slot, using placeholder", e)
                drawPlaceholder(cs, slotX, slotY, slotW, slotH)
            }
        } else {
            drawPlaceholder(cs, slotX, slotY, slotW, slotH)
        }

        // Caption
        if (!slot.caption.isNullOrBlank()) {
            try {
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                cs.setNonStrokingColor(0.3f, 0.3f, 0.3f)
                cs.beginText()
                cs.setFont(font, CAPTION_FONT_SIZE)
                cs.newLineAtOffset(slotX + 4f, slotY + 4f)
                cs.showText(slot.caption)
                cs.endText()
            } catch (e: Exception) {
                logger.warn("Failed to render caption", e)
            }
        }
    }

    /**
     * Loads image bytes, combines EXIF orientation with user rotation, and physically rotates pixels.
     * EXIF rotation is applied first (matching browser auto-rotation), then user rotation on top.
     * This ensures PDF output matches what the browser shows in the preview.
     */
    private fun loadImage(document: PDDocument, bytes: ByteArray, userRotation: Int): PDImageXObject {
        val exifDegrees = readJpegExifOrientation(bytes)
        val totalDegrees = (exifDegrees + userRotation) % 360

        val original = ImageIO.read(ByteArrayInputStream(bytes))
            ?: return PDImageXObject.createFromByteArray(document, bytes, "photo")

        val resized = resizeIfNeeded(original)
        val processed = if (totalDegrees != 0) rotateBufferedImage(resized, totalDegrees) else resized
        return JPEGFactory.createFromImage(document, processed, 0.88f)
    }

    private fun resizeIfNeeded(image: BufferedImage): BufferedImage {
        val maxDim = maxOf(image.width, image.height)
        if (maxDim <= MAX_IMAGE_DIMENSION) return image
        val scale = MAX_IMAGE_DIMENSION.toDouble() / maxDim
        val newW = (image.width * scale).toInt()
        val newH = (image.height * scale).toInt()
        val resized = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, newW, newH, null)
        g.dispose()
        return resized
    }

    /**
     * Reads EXIF orientation from JPEG bytes and returns clockwise degrees to apply (0, 90, 180, or 270).
     * Returns 0 for non-JPEG files or images without EXIF orientation data.
     */
    private fun readJpegExifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 12) return 0
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 0  // not JPEG

        var pos = 2
        while (pos + 4 <= bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) break
            val marker = bytes[pos + 1].toInt() and 0xFF
            val segLen = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (segLen < 2) break

            if (marker == 0xE1 && segLen >= 8) {
                val base = pos + 4
                if (base + 6 <= bytes.size &&
                    bytes[base]     == 0x45.toByte() && bytes[base + 1] == 0x78.toByte() &&
                    bytes[base + 2] == 0x69.toByte() && bytes[base + 3] == 0x66.toByte() &&
                    bytes[base + 4] == 0x00.toByte() && bytes[base + 5] == 0x00.toByte()
                ) {
                    return parseTiffOrientation(bytes, base + 6)
                }
            }
            pos += 2 + segLen
        }
        return 0
    }

    private fun parseTiffOrientation(bytes: ByteArray, tiffStart: Int): Int {
        if (tiffStart + 8 > bytes.size) return 0
        val le = bytes[tiffStart] == 0x49.toByte() && bytes[tiffStart + 1] == 0x49.toByte()

        fun short(p: Int): Int {
            if (p + 1 >= bytes.size) return 0
            val a = bytes[p].toInt() and 0xFF; val b = bytes[p + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun int32(p: Int): Int {
            if (p + 3 >= bytes.size) return 0
            val a = bytes[p].toInt() and 0xFF; val b = bytes[p+1].toInt() and 0xFF
            val c = bytes[p+2].toInt() and 0xFF; val d = bytes[p+3].toInt() and 0xFF
            return if (le) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        val ifdOffset = int32(tiffStart + 4)
        val ifdPos = tiffStart + ifdOffset
        if (ifdPos + 2 > bytes.size) return 0
        val entryCount = short(ifdPos)
        for (e in 0 until entryCount) {
            val ep = ifdPos + 2 + e * 12
            if (ep + 12 > bytes.size) break
            if (short(ep) == 0x0112) {  // Orientation tag
                return when (short(ep + 8)) {
                    3 -> 180
                    6 -> 90
                    8 -> 270
                    else -> 0
                }
            }
        }
        return 0
    }

    private fun rotateBufferedImage(src: BufferedImage, degrees: Int): BufferedImage {
        val norm = ((degrees % 360) + 360) % 360
        val w = src.width
        val h = src.height
        val (newW, newH) = if (norm == 90 || norm == 270) Pair(h, w) else Pair(w, h)
        val dest = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g = dest.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val at = AffineTransform()
        when (norm) {
            90 -> { at.translate(newW.toDouble(), 0.0); at.rotate(Math.PI / 2) }
            180 -> { at.translate(newW.toDouble(), newH.toDouble()); at.rotate(Math.PI) }
            270 -> { at.translate(0.0, newH.toDouble()); at.rotate(-Math.PI / 2) }
        }
        g.drawRenderedImage(src, at)
        g.dispose()
        return dest
    }

    /**
     * Draws an image using cover-fit (aspect-fill) within the given rectangle, clipping overflow.
     * offsetX/offsetY (0.0–1.0) control the crop anchor: 0=left/top, 0.5=center, 1.0=right/bottom.
     */
    private fun drawCoverFitImage(
        cs: PDPageContentStream,
        image: PDImageXObject,
        boxX: Float,
        boxY: Float,
        boxW: Float,
        boxH: Float,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f
    ) {
        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()

        val scale = maxOf(boxW / imgW, boxH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        // Apply offset: 0.0 = align start edges, 1.0 = align end edges
        val drawX = boxX + (boxW - drawW) * offsetX
        val drawY = boxY + (boxH - drawH) * (1f - offsetY)  // Y-flip: PDF Y goes up, CSS Y goes down

        cs.saveGraphicsState()
        cs.addRect(boxX, boxY, boxW, boxH)
        cs.clip()
        cs.drawImage(image, drawX, drawY, drawW, drawH)
        cs.restoreGraphicsState()
    }

    private fun drawPlaceholder(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        cs.setNonStrokingColor(0.85f, 0.85f, 0.85f)
        cs.addRect(x, y, w, h)
        cs.fill()
    }
}

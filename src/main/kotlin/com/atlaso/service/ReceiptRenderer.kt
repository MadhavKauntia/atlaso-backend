package com.atlaso.service

import com.atlaso.domain.order.Order
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders a simple, no-GST payment receipt PDF for a paid [Order].
 * Uses an embedded Roboto font (which includes the ₹ glyph) so amounts render
 * with the rupee symbol.
 */
@Component
class ReceiptRenderer {

    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val ist = ZoneId.of("Asia/Kolkata")

    fun render(order: Order): ByteArray {
        val doc = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val regular = PDType0Font.load(doc, requireNotNull(javaClass.getResourceAsStream("/fonts/Roboto-Regular.ttf")) { "Roboto-Regular.ttf missing" })
            val bold = PDType0Font.load(doc, requireNotNull(javaClass.getResourceAsStream("/fonts/Roboto-Bold.ttf")) { "Roboto-Bold.ttf missing" })

            val left = 60f
            val right = PDRectangle.A4.width - 60f // right edge of content
            var y = PDRectangle.A4.height - 80f

            PDPageContentStream(doc, page).use { cs ->
                fun text(s: String, font: PDFont, size: Float, x: Float = left, color: Triple<Float, Float, Float> = Triple(0.1f, 0.1f, 0.1f)) {
                    cs.setNonStrokingColor(color.first, color.second, color.third)
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(x, y)
                    cs.showText(s)
                    cs.endText()
                }

                fun row(label: String, amount: String, font: PDFont, size: Float) {
                    text(label, font, size)
                    val w = font.getStringWidth(amount) / 1000f * size
                    text(amount, font, size, x = right - w)
                }

                fun divider() {
                    cs.setStrokingColor(0.8f, 0.78f, 0.72f)
                    cs.setLineWidth(0.8f)
                    cs.moveTo(left, y)
                    cs.lineTo(right, y)
                    cs.stroke()
                }

                fun gap(px: Float) { y -= px }

                // Brand
                text("ATLASO", bold, 22f)
                gap(18f)
                text("Travel memories, beautifully preserved.", regular, 10f, color = Triple(0.45f, 0.42f, 0.38f))
                gap(34f)

                text("PAYMENT RECEIPT", bold, 13f)
                gap(12f)
                divider()
                gap(22f)

                text("Receipt #:  ATL-R-${order.number}", regular, 11f)
                gap(16f)
                text("Order #:    ATL-${order.number}", regular, 11f)
                gap(16f)
                val date = order.createdAt?.atZone(ist)?.format(dateFmt) ?: "-"
                text("Date:       $date", regular, 11f)
                gap(30f)

                text("CUSTOMER", bold, 11f, color = Triple(0.45f, 0.42f, 0.38f))
                gap(16f)
                text(order.customerName ?: "Atlaso customer", regular, 11f)
                order.customerEmail?.let { gap(15f); text(it, regular, 11f) }
                gap(30f)

                text("ORDER", bold, 11f, color = Triple(0.45f, 0.42f, 0.38f))
                gap(16f)
                text(order.bookTitle?.let { "$it Travel Photobook" } ?: "Travel Photobook", regular, 11f)
                gap(18f)
                // The captured amount is the discounted total; the subtotal is that
                // plus whatever the coupon took off, so the two always reconcile.
                val discount = order.discountMinor ?: 0L
                val subtotalMinor = order.amountMinor + discount
                row("${order.quantity} × Hardcover Photobook", money(subtotalMinor), regular, 11f)
                gap(16f)
                if (discount > 0L) {
                    val couponLabel = order.couponCode?.let { "Coupon ($it)" } ?: "Discount"
                    row(couponLabel, "-${money(discount)}", regular, 11f)
                    gap(16f)
                }
                row("Shipping", money(0), regular, 11f)
                gap(12f)
                divider()
                gap(20f)
                row("TOTAL", money(order.amountMinor), bold, 13f)
                gap(32f)

                text("Payment status:  ${order.status}", regular, 11f)
                gap(16f)
                text("Payment method:  ${methodLabel(order.paymentMethod)}", regular, 11f)
                gap(16f)
                order.razorpayPaymentId?.let { text("Razorpay Payment ID:  $it", regular, 10f, color = Triple(0.45f, 0.42f, 0.38f)) }
                gap(34f)

                divider()
                gap(20f)
                text("Thank you for choosing Atlaso.", regular, 11f)
                gap(16f)
                text("myatlaso.com", regular, 10f, color = Triple(0.45f, 0.42f, 0.38f))
            }

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    /** Formats paise as ₹ with 2 decimals only when the amount isn't whole rupees. */
    private fun money(minor: Long): String {
        val fmt = NumberFormat.getNumberInstance(Locale("en", "IN"))
        if (minor % 100L == 0L) {
            fmt.maximumFractionDigits = 0
        } else {
            fmt.minimumFractionDigits = 2
            fmt.maximumFractionDigits = 2
        }
        return "₹" + fmt.format(BigDecimal(minor).movePointLeft(2))
    }

    private fun methodLabel(method: String?): String = when (method?.lowercase()) {
        null, "" -> "Online"
        "upi" -> "UPI"
        "card" -> "Card"
        "netbanking" -> "Netbanking"
        "wallet" -> "Wallet"
        "emi" -> "EMI"
        else -> method.replaceFirstChar { it.uppercase() }
    }
}

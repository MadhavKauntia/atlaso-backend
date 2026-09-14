package com.atlaso.repository

import com.atlaso.domain.order.Checkout
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CheckoutRepository : JpaRepository<Checkout, UUID> {
    fun findByRazorpayOrderId(razorpayOrderId: String): Checkout?
}

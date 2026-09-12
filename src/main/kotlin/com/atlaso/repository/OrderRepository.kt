package com.atlaso.repository

import com.atlaso.domain.order.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface OrderRepository : JpaRepository<Order, UUID> {
    fun findFirstByTripIdOrderByCreatedAtDesc(tripId: UUID): Order?
    fun findByRazorpayPaymentId(razorpayPaymentId: String): Order?

    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    fun nextNumber(): Long
}

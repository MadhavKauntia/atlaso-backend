package com.atlaso.repository

import com.atlaso.domain.coupon.Coupon
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CouponRepository : JpaRepository<Coupon, UUID> {
    @Query("SELECT c FROM Coupon c WHERE UPPER(c.code) = UPPER(:code)")
    fun findByCode(@Param("code") code: String): Coupon?

    fun findAllByOrderByCreatedAtDesc(): List<Coupon>

    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id")
    fun incrementUsage(@Param("id") id: UUID)
}

package com.atlaso.service

/** Central pricing constants shared across order and coupon logic. */
object Pricing {
    /** Flat all-inclusive unit price actually charged, in paise (Rs. 1999 launch price;
     *  Rs. 2499 is shown struck-through on the client as the pre-launch price). */
    const val UNIT_PRICE_MINOR = 199900L
}

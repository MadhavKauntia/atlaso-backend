# Coupon → Razorpay Offer Implementation Plan

## Goal

Add a coupon-code input to the checkout page. When a valid code is entered, create the
Razorpay order with the **native Razorpay Offer** linked to it (via the `offers` array),
so Razorpay applies the discount at payment time and the `order_id` handed to Checkout is
already bound to the coupon.

## Chosen model (decided)

- **Discount engine:** Razorpay **native Offers**. Each coupon code maps to a Razorpay
  `offer_id` (created on the Razorpay Dashboard). Razorpay applies the actual discount at
  pay time based on the offer's rules; our backend never computes the final charged amount.
- **Coupon store:** a new `coupons` DB table + a `POST /api/coupons/validate` endpoint used
  by the checkout page to validate the code and preview the discount before payment.

## How Razorpay Offers work (from the docs)

- Offers are created on the Razorpay **Dashboard** and return an id like
  `offer_ANZoaxsOww2X53`.
- You link an offer to an order by passing `offers: ["offer_..."]` when calling
  `POST https://api.razorpay.com/v1/orders`. The **full (list) amount** is still sent as
  `amount` — Razorpay subtracts the offer discount itself.
- `force_offer: true` forces the single offer on Checkout (customer can only pay via that
  offer's method); only **one** offer id is allowed in the array when forcing.
- The captured `payment.amount` returned later is the **already-discounted** amount.

### Key implications for our code
1. **Do NOT pre-discount the amount.** `create-order` must send the full `199900` (× qty).
   Razorpay handles the reduction. Pre-discounting breaks the offer's `min_amount` check
   and can double-discount.
2. Our existing `OrderService.createPaidOrder` already records
   `payment?.amountMinor` (`OrderService.kt:58`), i.e. the **discounted captured amount**,
   so the receipt/DB amount is automatically correct. We only need to *also* persist which
   coupon/offer was used and the savings for reporting.
3. Set the Dashboard offer's **"Show Offer on Checkout" toggle OFF** so it only applies when
   we explicitly link it via the `offers` array (i.e. only when the user entered the code).
4. Razorpay rejects the order if the offer is ineligible (below `min_amount`, expired, wrong
   method). We must handle that failure path explicitly.

## Current state (reference)

| Concern | Location |
|---|---|
| Create-order endpoint | `PaymentController.kt:32` |
| Razorpay order call (no `offers` today) | `PaymentService.createOrder` `PaymentService.kt:75` |
| Create-order DTOs | `PaymentDtos.kt:5` (`CreateOrderRequest`), `:11` (`CreateOrderResponse`) |
| Verify endpoint | `PaymentController.kt:52` |
| Order recording (uses captured amount) | `OrderService.createPaidOrder` `OrderService.kt:40`, amount at `:58` |
| Flat unit price | `OrderService.kt:11` `UNIT_PRICE_MINOR = 199900` |
| Order entity | `domain/order/Order.kt` |
| Latest migration | `V11__add_order_fulfilment.sql` → **next is V12** |

No coupon/offer/discount code exists anywhere today.

---

## Implementation

### 1. Database — `V12__add_coupons.sql`

```sql
CREATE TABLE coupons (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(64)  NOT NULL,          -- stored uppercase, unique
    razorpay_offer_id   VARCHAR(64)  NOT NULL,          -- offer_... from Dashboard
    description         VARCHAR(255),
    -- preview-only fields (Razorpay is the source of truth for the real discount):
    discount_type       VARCHAR(16),                    -- PERCENT | FLAT
    discount_value      BIGINT,                         -- percent (e.g. 20) or flat paise
    max_discount_minor  BIGINT,                          -- cap for percent, nullable
    min_amount_minor    BIGINT,                          -- mirror of offer min_amount, nullable
    force_offer         BOOLEAN NOT NULL DEFAULT FALSE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from          TIMESTAMP,
    valid_until         TIMESTAMP,
    max_uses            INTEGER,                         -- null = unlimited
    used_count          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ux_coupons_code ON coupons (UPPER(code));

-- Persist which coupon/offer was applied to an order (amount_minor already = discounted).
ALTER TABLE orders ADD COLUMN coupon_code       VARCHAR(64);
ALTER TABLE orders ADD COLUMN razorpay_offer_id VARCHAR(64);
ALTER TABLE orders ADD COLUMN discount_minor    BIGINT;   -- list amount - captured amount
```

> The preview fields (`discount_type/value/max/min`) are only for showing "You save ₹X" in
> the UI. The authoritative discount is whatever Razorpay applies. If we later want the
> preview to be exact, we can call Razorpay's fetch-offer API instead — out of scope here.

### 2. Domain + repository

- `domain/coupon/Coupon.kt` — plain JPA entity mapping the table above (mirrors the style of
  `Order.kt`).
- `repository/CouponRepository.kt`:
  ```kotlin
  interface CouponRepository : JpaRepository<Coupon, UUID> {
      @Query("SELECT c FROM Coupon c WHERE UPPER(c.code) = UPPER(:code)")
      fun findByCode(code: String): Coupon?

      @Modifying
      @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id")
      fun incrementUsage(id: UUID)
  }
  ```

### 3. `CouponService`

```kotlin
data class CouponValidation(
    val valid: Boolean,
    val code: String,
    val offerId: String?,       // null when invalid
    val forceOffer: Boolean,
    val discountMinor: Long,    // previewed savings (0 when invalid)
    val finalMinor: Long,       // list - discount (preview only)
    val message: String?,       // reason when invalid
)
```

`validate(code, amountMinor)`:
1. Look up by code; if missing/`!active` → invalid ("Invalid coupon").
2. Check `valid_from`/`valid_until` window against `Instant.now()` → "Coupon expired / not yet active".
3. Check `max_uses` vs `used_count` → "Coupon fully redeemed".
4. Check `min_amount_minor` vs `amountMinor` → "Add more to use this coupon".
5. Compute preview `discountMinor` from `discount_type/value` (cap with `max_discount_minor`).
6. Return `CouponValidation(valid=true, offerId=..., ...)`.

`resolveForOrder(code, amountMinor): Coupon` — same checks, returns the entity (or throws
`CouponInvalidException`) for use during create-order. **Server-side re-validation — never
trust the client's discount.**

### 4. `POST /api/coupons/validate` (new `CouponController`)

- Request: `ValidateCouponRequest(code: String, quantity: Int? = 1)`
- Computes `amountMinor = qty * UNIT_PRICE_MINOR` (move `UNIT_PRICE_MINOR` to a shared
  `Pricing` constant/object so both services use it).
- Response: `ValidateCouponResponse(valid, code, discountMinor, finalMinor, message)`
  (do **not** leak `offerId` to the client — keep it server-side).
- No auth needed to preview (consistent with `create-order` being unauthenticated), or gate
  behind JWT if we prefer — checkout is already authenticated for verify.

### 5. Thread the offer into order creation

**`PaymentDtos.CreateOrderRequest`** — add `couponCode: String? = null`.

**`PaymentService.createOrder`** (`PaymentService.kt:75`) — add optional params and payload:
```kotlin
fun createOrder(
    amountMinor: Long,
    currency: String,
    receipt: String?,
    offerIds: List<String>? = null,
    forceOffer: Boolean = false,
): RazorpayOrder {
    ...
    val payload = buildMap<String, Any> {
        put("amount", amountMinor)          // FULL list amount, not discounted
        put("currency", currency)
        put("receipt", receipt ?: "rcpt_${System.currentTimeMillis()}")
        if (!offerIds.isNullOrEmpty()) {
            put("offers", offerIds)
            if (forceOffer) put("force_offer", true)  // only valid with a single offer id
        }
    }
    ...
}
```

**`PaymentController.createOrder`** (`PaymentController.kt:32`):
```kotlin
val coupon = request.couponCode
    ?.takeIf { it.isNotBlank() }
    ?.let { couponService.resolveForOrder(it, request.amount) }  // re-validate server-side

val order = paymentService.createOrder(
    amountMinor = request.amount,            // still full amount
    currency = request.currency ?: "INR",
    receipt = request.receipt,
    offerIds = coupon?.let { listOf(it.razorpayOfferId) },
    forceOffer = coupon?.forceOffer ?: false,
)
```
- On `CouponInvalidException` → `400 { "error": "<reason>" }`.
- On Razorpay rejecting the offer (`RazorpayException` from an eligibility failure) →
  `400 { "error": "Coupon not applicable to this order" }`. Decide policy: **fail loudly**
  (recommended) rather than silently creating an undiscounted order the user didn't expect.

### 6. Persist the applied coupon on the order

**`VerifyPaymentRequest`** (`PaymentDtos.kt:17`) — add `couponCode: String? = null`.

**`PaymentController.verifyPayment`** — pass `request.couponCode` into `createPaidOrder`.

**`OrderService.createPaidOrder`** (`OrderService.kt:40`):
- Accept `couponCode: String?`.
- If present and valid, set `order.couponCode`, `order.razorpayOfferId = coupon.razorpayOfferId`.
- `order.discountMinor = (qty * UNIT_PRICE_MINOR) - amountMinor` (list minus captured;
  `amountMinor` is already the discounted `payment.amountMinor`).
- After a successful save, `couponRepository.incrementUsage(coupon.id)`.
- Keep it best-effort/inside the existing try — a coupon bookkeeping failure must not fail a
  payment that already succeeded.

**`Order.kt`** — add `couponCode`, `razorpayOfferId`, `discountMinor` fields mapped to the new
columns. **`ReceiptRenderer` / admin DTO / email** — optionally show the discount line
(list price, "Coupon SAVE20 −₹400", total). Nice-to-have, not required for correctness.

### 7. Frontend (repo: `atlaso-frontend`)

The checkout page is `src/app/trips/[tripId]/checkout/page.tsx`. It's a client component using
plain `useState`, inline `React.CSSProperties` with `--sb-*` design tokens, and a two-column
layout (form left, sticky order summary right). Payment goes through `src/lib/api.ts`
(`createRazorpayOrder` `api.ts:156`, `verifyRazorpayPayment` `api.ts:171`) and
`src/lib/razorpay.ts`. Price is hardcoded `BOOK_PRICE = 1999` (`page.tsx:13`).

#### 7a. API client — `src/lib/api.ts`

Add a validate helper and extend the two existing payment calls.

```typescript
export interface CouponPreview {
  valid: boolean;
  code: string;
  discountMinor: number;   // paise saved (preview)
  finalMinor: number;      // paise payable (preview)
  message?: string;        // reason when !valid
}

/** Validates a coupon and previews the discount. */
export async function validateCoupon(code: string, quantity = 1): Promise<CouponPreview> {
  const res = await apiFetch(`${BASE}/coupons/validate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, quantity }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
```

- `createRazorpayOrder` (`api.ts:156`): add a 4th arg `couponCode?: string` and include it in
  the JSON body: `JSON.stringify({ amount, currency, receipt, couponCode })`.
- `verifyRazorpayPayment` (`api.ts:171`): add `couponCode?: string` to the payload type and it
  is already spread into the request body — just pass it from the caller.

#### 7b. Checkout page — `src/app/trips/[tripId]/checkout/page.tsx`

**State (near `page.tsx:43`):**
```typescript
const [couponInput, setCouponInput] = useState("");
const [coupon, setCoupon] = useState<CouponPreview | null>(null);  // applied coupon
const [couponError, setCouponError] = useState<string | null>(null);
const [checkingCoupon, setCheckingCoupon] = useState(false);
```

**Apply handler:**
```typescript
const applyCoupon = async () => {
  const code = couponInput.trim();
  if (!code) return;
  setCheckingCoupon(true);
  setCouponError(null);
  try {
    const preview = await validateCoupon(code, qty);
    if (preview.valid) {
      setCoupon(preview);
    } else {
      setCoupon(null);
      setCouponError(preview.message ?? "Invalid coupon");
    }
  } catch {
    setCouponError("Couldn't check that coupon. Try again.");
  } finally {
    setCheckingCoupon(false);
  }
};
const removeCoupon = () => { setCoupon(null); setCouponInput(""); setCouponError(null); };
```

**Totals (replace the `total` math around `page.tsx:62`):** keep the **list amount** as what
we send to `create-order` (Razorpay applies the real discount); use the preview only for
display.
```typescript
const listTotal = BOOK_PRICE * qty;                       // rupees, sent to Razorpay
const discount = coupon ? Math.round(coupon.discountMinor / 100) : 0;  // preview only
const payable = coupon ? Math.round(coupon.finalMinor / 100) : listTotal;
```

**Coupon UI** — a new block, styled like the existing summary rows, placed in the order
summary panel just above the Total row (after the rows `.map` at `page.tsx:285`). Reuse
`fieldStyle`, `--sb-*` tokens, and the pill/`FieldError` patterns already in the file:
```tsx
{/* Coupon */}
<div style={{ padding: "12px 0", borderTop: DASH }}>
  {coupon ? (
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: 14 }}>
      <div style={{ color: "var(--sb-green)", fontWeight: 700 }}>
        ✓ {coupon.code} applied
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <span style={{ color: "var(--sb-cream)", fontWeight: 700 }}>−₹{discount.toLocaleString("en-IN")}</span>
        <button onClick={removeCoupon} style={{ background: "none", border: "none", color: "var(--sb-muted)", cursor: "pointer", fontSize: 12, textDecoration: "underline" }}>Remove</button>
      </div>
    </div>
  ) : (
    <div style={{ display: "flex", gap: 8 }}>
      <input
        value={couponInput}
        onChange={(e) => setCouponInput(e.target.value.toUpperCase())}
        onKeyDown={(e) => e.key === "Enter" && applyCoupon()}
        placeholder="Coupon code"
        style={{ ...fieldStyle(!!couponError), flex: 1, textTransform: "uppercase" }}
      />
      <button onClick={applyCoupon} disabled={checkingCoupon || !couponInput.trim()}
        style={{ padding: "0 18px", borderRadius: 12, border: "1px solid var(--sb-panel-2)", background: "var(--sb-bg)", color: "var(--sb-cream)", fontWeight: 700, cursor: "pointer", whiteSpace: "nowrap" }}>
        {checkingCoupon ? "…" : "Apply"}
      </button>
    </div>
  )}
  {couponError && <FieldError>{couponError}</FieldError>}
</div>
```
- Show the discount as a line in the summary rows and change the **Total** (`page.tsx:289`)
  and the **Pay button label** (`page.tsx:308`) to use `payable` instead of `total`.
- Re-validate on quantity change: if `coupon` is set and `qty` changes, re-run `applyCoupon`
  (or clear the coupon) so `min_amount` / preview stays correct. (qty comes from the URL here,
  so this only matters if qty can change on the page — otherwise validate once on apply.)

**Wire the code into payment (`handlePay`, `page.tsx:79`):**
```typescript
// amount stays the FULL list amount — Razorpay subtracts the offer discount
const amountPaise = Math.round(listTotal * 100);
const order = await createRazorpayOrder(amountPaise, "INR", `trip_${tripId}`, coupon?.code);
```
and pass `couponCode: coupon?.code` into `verifyRazorpayPayment({ ... })` (`page.tsx:112`).

**Razorpay widget:** no change needed — it already receives `order_id`/`amount` from the
order response, and the linked offer surfaces automatically inside Checkout. Keep `amount:
order.amount` (the full amount echoed by the backend).

#### 7c. Frontend edge cases

- If `create-order` now returns a 400 (coupon became invalid between validate and pay, or
  Razorpay rejected the offer), `createRazorpayOrder` already throws on `!res.ok` and
  `handlePay`'s catch sets `payError` — surface a friendly "This coupon is no longer valid,
  please remove it and try again."
- The previewed `discount`/`payable` are indicative; the **authoritative** amount is what
  Razorpay charges and what the confirmation/receipt shows (already driven by the captured
  payment on the backend). Consider a small "final discount applied at payment" note.

---

## Dashboard / ops setup (manual, one-time per coupon)

1. Create the offer in Razorpay Dashboard → copy the `offer_id`.
2. Turn **"Show Offer on Checkout" OFF** so it only applies when linked via code.
3. Insert a `coupons` row: `code`, `razorpay_offer_id`, validity, `max_uses`, and the
   preview `discount_type/value`. (Seed via SQL, or add a small admin endpoint later —
   `AdminController` already exists with `X-Admin-Key` auth if we want managed CRUD.)

## Edge cases & decisions to confirm during build

- **Undiscounted fallback vs hard fail** when Razorpay rejects the offer → plan recommends
  hard-fail with a clear message so the user isn't silently charged full price.
- **`force_offer`**: only set when the coupon is meant to *restrict* payment method; leave
  `false` for broad "applies to all methods" offers. Only one offer id may be forced.
- **Abuse / usage cap**: `used_count` is incremented only on a recorded paid order, so it
  counts real redemptions (not just validations). Per-user caps are out of scope (no
  coupon-per-user table) — add later if needed.
- **Currency**: INR only, unchanged.

## Testing

- `CouponServiceTest`: expired, not-yet-active, inactive, over `max_uses`, below
  `min_amount`, happy path (preview math + cap).
- `PaymentService.createOrder`: payload includes `offers`/`force_offer` when offer ids passed,
  omits them otherwise (extract payload building to make it unit-testable).
- Controller/integration: `/api/coupons/validate` valid + invalid; `create-order` with a good
  code, an unknown code (400), and an ineligible offer (400).
- `OrderService`: `discount_minor` computed correctly and `used_count` incremented once
  (idempotent on repeat verify of the same payment id).

## File-change checklist

- [ ] `src/main/resources/db/migration/V12__add_coupons.sql` (new)
- [ ] `domain/coupon/Coupon.kt` (new)
- [ ] `repository/CouponRepository.kt` (new)
- [ ] `service/CouponService.kt` (new) + `CouponInvalidException`
- [ ] `controller/CouponController.kt` (new) + `ValidateCoupon` DTOs
- [ ] shared `UNIT_PRICE_MINOR` → `Pricing` object (small refactor from `OrderService.kt:11`)
- [ ] `PaymentDtos.kt`: `couponCode` on `CreateOrderRequest` + `VerifyPaymentRequest`
- [ ] `PaymentService.createOrder`: `offers` / `force_offer` support
- [ ] `PaymentController`: resolve coupon → offer on create-order; pass code on verify; error mapping
- [ ] `OrderService.createPaidOrder`: persist coupon/offer/discount, increment usage
- [ ] `domain/order/Order.kt`: new columns
- [ ] (optional) `ReceiptRenderer` / `AdminOrderDto` / `EmailService`: show discount line
- [ ] tests as above

Frontend (`atlaso-frontend`):
- [ ] `src/lib/api.ts`: add `validateCoupon` + `CouponPreview`; add `couponCode` to
      `createRazorpayOrder` and `verifyRazorpayPayment`
- [ ] `src/app/trips/[tripId]/checkout/page.tsx`: coupon state + `applyCoupon`/`removeCoupon`,
      coupon UI block in the summary, `listTotal`/`discount`/`payable` math, Total + Pay-button
      labels, pass `coupon?.code` into create-order and verify, 400 handling
```

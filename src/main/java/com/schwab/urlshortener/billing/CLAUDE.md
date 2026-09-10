# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/billing/`.

## Call chain

```
UrlShortenerServiceImpl / RedirectController
        │ (async, fire-and-forget)
        ▼
UsageMeteringService.recordApiCall / recordRedirect
        │ try atomic UPDATE first
        ├─ row exists → repository.incrementApiCallCount/incrementRedirectCount (done)
        └─ 0 rows affected → UsageRecordCreator.createInitialRecord (new bean, REQUIRES_NEW)
                │
                ├─ succeeds → done
                └─ DataIntegrityViolationException (lost the create race)
                        → UsageMeteringService retries the increment on the row the winner just created

BillingController / InvoiceService
        ▼
BillingService.getCurrentStatement / getStatementForPeriod
        │ reads TenantUsageRecordRepository directly (read-only), applies BillingProperties pricing
        ▼
BillingStatementResponse  ── consumed as-is by BillingController, or frozen into an Invoice by InvoiceService
```

`InvoiceService` never touches `TenantUsageRecordRepository` directly — it always goes through
`BillingService.getStatementForPeriod`, specifically so invoice generation and "check my current bill" share
one rating code path. `BillingService` itself never mutates usage; only `UsageMeteringService`/
`UsageRecordCreator` write to `TenantUsageRecordRepository`.

## The insert-or-retry mechanism (UsageMeteringService + UsageRecordCreator)

The "increment first, create-on-miss" pattern exists to avoid depending on `INSERT ... ON CONFLICT` /
`MERGE` syntax, which differs across H2 and Postgres/MySQL — an atomic `UPDATE` and a plain `save()` both
work identically everywhere. The two failure-prone steps and why each is shaped the way it is:

1. **`incrementApiCallCount`/`incrementRedirectCount`** are `@Modifying @Query` UPDATEs, checked by
   **return value** (rows affected), not by a preceding `findBy...` — this is what avoids a
   read-then-write race on the common path (row already exists for this tenant/period, which is true for
   every event after the first one in a month).
2. On a `0` return (first event of the month for this tenant), `UsageRecordCreator.createInitialRecord`
   attempts the insert **in its own `REQUIRES_NEW` transaction**, not a private method on
   `UsageMeteringService`. This matters because `@Transactional(REQUIRES_NEW)` only applies through the
   Spring AOP proxy — a same-class call would silently run in the *caller's* transaction, and a failed
   insert there (the unique constraint on `(tenantId, periodYearMonth)`) would mark that whole transaction
   rollback-only, breaking the retry-increment step below. As its own bean, only the insert attempt itself
   rolls back; `UsageMeteringService`'s outer transaction stays usable.
3. If the insert throws `DataIntegrityViolationException` (a concurrent request's `createInitialRecord` won
   first), `UsageMeteringService` catches it and re-issues the exact same atomic `UPDATE` from step 1 —
   which now succeeds against the row the winner just created. No third case is handled beyond this; if that
   retry itself somehow returns 0 rows, the event is silently lost (would mean the winner's insert and this
   retry both missed the same row, not something the test suite exercises).

`UsageMeteringServiceTest` covers exactly these three branches (existing row / no row+create / no row+lost
race+retry) — read it alongside the source if changing this logic, since it's the executable spec for the
concurrency contract described above.

## Invoice numbering

`InvoiceService.generateInvoice` mints `String.format("INV-%s-%06d", period, tenantId)` — e.g.
`INV-2026-08-000001`. This is deterministic from `(tenantId, billingPeriod)` alone, which is already the
`Invoice` table's unique constraint (`uq_invoice_tenant_period`), so no separate sequence table or
post-insert "patch in the generated number" step is needed — the number is computable before the row is
even built. Consequence worth knowing: the last 6 digits are the **tenant ID**, not a per-tenant invoice
sequence — two different tenants invoicing the same period get numbers that differ only in that suffix, and
a single tenant's invoice numbers across different periods differ only in the period segment. If a
sequential "invoice #1, #2, #3 for this tenant" numbering scheme is ever wanted, this scheme cannot produce
it without a real migration.

## Duplicate-invoice race handling

`generateInvoice`'s `findByTenantIdAndBillingPeriod` pre-check is fast-fail UX only, same pattern as
short-code creation in `UrlShortenerServiceImpl` — the real guarantee is the DB unique constraint. Unlike the
short-code path, though, a lost race here **re-fetches the winner's invoice** and throws
`DuplicateInvoiceException` carrying the *winner's* invoice number, so the loser's client sees the same
"here's the invoice number that already exists" message a normal duplicate check would give — it doesn't
just report a generic conflict. `InvoiceServiceTest#generateInvoice_racesPastPreCheck_stillThrowsDuplicateConflict_notA500`
is the regression test for this; the code comment above it notes this exact check-then-save race shape was
already fixed elsewhere in the codebase (short-code creation) before a production review caught that this
class still had the unfixed version — a reminder that this pattern (pre-check + catch
`DataIntegrityViolationException` on the real unique constraint) needs to be applied to *every* new
uniqueness-guarded creation path added here, not assumed to be handled generically.

## InvoiceStatus — VOID exists but is unreachable

The enum defines `ISSUED` and `VOID`, but nothing in this package ever sets `VOID` — every `Invoice` is
built with `.status(InvoiceStatus.ISSUED)` and nothing transitions it afterward (no cancel/correction
endpoint exists). `VOID` is scaffolding for a future correction mechanism, not a currently reachable state.
If you're investigating "why can't I void an invoice," the answer is: that capability doesn't exist yet, the
enum value is just staged for it.

## Currency

Every monetary field, everywhere in this package (`BillingProperties.PlanPricing`,
`BillingStatementResponse`, `Invoice`, `InvoiceResponse`), is `long` cents — never a floating-point type.
`BillingService`'s overage math (`Math.max(0, used - included) * centsPerUnit`, summed, plus base fee) is
pure integer arithmetic, so there's no rounding step anywhere to get wrong. The only place cents becomes a
decimal string is display-only, in `InvoicePdfGenerator.formatCents` (`cents / 100.0` fed to
`String.format("$%,.2f", ...)`) — that conversion never flows back into a stored or computed monetary value,
it only ever produces PDF text.

## InvoicePdfGenerator reads only the frozen entity

`generate(Invoice invoice, String tenantName)` takes plain scalar fields off the `Invoice` row — it does not
re-call `BillingService` or accept a `BillingStatementResponse`. This is deliberate: regenerating the PDF a
year after issuance must render the exact numbers that were frozen at `issuedAt`, not whatever the tenant's
current usage/plan would compute to today. OpenPDF (not an HTML/templating pipeline) was chosen because the
layout is a fixed handful of key/value pairs and one small table — adding a template engine would be more
moving parts than the direct programmatic API costs here. If the invoice layout ever grows materially more
complex (multiple line-item types, conditional sections, branding per tenant), that trade-off should be
revisited rather than assumed to still hold.

## Known traps (don't "fix" these without reading README.md §10 first)

- **No closed billing periods.** A tenant can invoice the *current, still-accruing* month early
  (`generateInvoice` only rejects *future* periods, via `validatePeriod`'s `period.compareTo(current) > 0`
  check — the current period itself is fair game). Once that invoice exists, `DuplicateInvoiceException`
  then permanently blocks ever generating the *correct* final invoice for that month. This is a known,
  named gap (README.md §10 "Billing correctness"), not something a local fix in `InvoiceService` should
  paper over (e.g. silently allowing regeneration would break the "invoices are immutable" invariant
  documented on `Invoice` itself) — it needs an explicit period-close concept, which doesn't exist here.
- **No versioned pricing snapshots.** `BillingService.getStatementForPeriod` rates *historical* usage
  against the tenant's **current** `BillingProperties` pricing and **current** plan — there is no stored
  record of what the pricing was at the time. Changing `app.billing.plans.*` retroactively changes what
  every not-yet-issued invoice for a past period would compute to, and even an *already-issued* invoice
  would compute differently if it were ever regenerated (it can't be, per the immutability guarantee, but a
  bug that bypassed that guarantee would silently produce wrong historical amounts, not an obviously-wrong
  error).

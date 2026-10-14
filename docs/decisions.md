# Facts, recommendations and open questions

This file separates three kinds of statement, as the project brief requires:

- **Verified fact** — read directly from the six supplied workbooks or the three Testora template images, with the evidence.
- **Recommended improvement** — a rule the app applies because the workbooks show a defect; it changes how PLASMACO works and should be agreed.
- **Assumption requiring confirmation** — the workbooks are silent; the app ships with the default shown, every one changeable by an administrator, and seeds no business data PLASMACO has not supplied.

The full evidence is in the discovery report (Claude Doc "PLASMACO Operations App — Discovery Report", sections 1–4).

## Verified facts (from the workbooks)

| Fact | Evidence | How the app uses it |
|---|---|---|
| The six files are successive saves (29 Sep – 8 Oct 2026) of one macro-enabled workbook, *Finished Goods & Raw Material Stock 2026* | File dates and sheet contents; -43 is a superset of the others | The importer accepts any of them and imports only new days from later saves (idempotency keys per product, date and movement) |
| 191 daily blocks from 1 Feb to 7 Oct 2026; 132 HDPE PE100 pipe products in metres; one aggregate raw-material balance in kg | Parsed by `LegacyStockParser`; integration test on the real files | Products become master records (DN, PN, generated code); the resin balance maps to one raw-material item chosen at import |
| No daily production report exists in the files: no shift, line, machine, operator, target, downtime, scrap or quality data | Every sheet inspected | Lines, machines, shifts, targets, run rates and categories start empty; nothing is invented |
| Raw-material issue is computed (inward metres × kg/m), not measured | ISSUE formulas | Issue and return are recorded store movements; theoretical resin is computed separately for variance |
| kg per metre factors differ from ISO 4427-2 in many blocks (e.g. Sept theoretical resin +6.2 % vs ISO weights) | Section 3, item 8 of the discovery report | kg/m is held once in the product master, seeded from ISO 4427-2 values; each product whose stock-sheet weight differs carries a review note |
| Negative balances occurred (FG DN110 PN10 −78.5 m on 29 Apr; resin down to −15,723.4 kg in September) | Closing cells | Negative stock is blocked by default |
| Importing the newest workbook reproduces its own resin balance exactly (58,300.60 kg) with 148 explicit reconciliation movements | `LegacyWorkbookIntegrationTest` on the real files | Every discontinuity in the workbook becomes a visible, labelled reconciliation instead of a silent change |

## Recommended improvements (agree with PLASMACO before go-live)

1. **One ledger, no typed balances.** Balances are always the sum of posted movements; openings and closings are never typed. Corrections go through adjustments that need a reason and an approver.
2. **Production does not move stock by itself.** A storekeeper posts a production receipt against an *approved* report entry, never more than its good output. (This follows the brief: "Do not automatically increase inventory just because production has been recorded.")
3. **Negative stock is blocked.** The auditable override exists but is *off* until an administrator enables it in Settings (device) and `PLASMACO_ALLOW_NEGATIVE_OVERRIDE=true` (server). When on, only Manager and Administrator roles hold the override permission and each override needs a note and is audited.
4. **Measured raw-material issue.** Resin issued to and returned from the line are store movements; consumption = issued − returned ± line-side stock; theoretical resin and the variance are reported beside it.
5. **Structured products.** DN, PN, grade and kg/m in a master record with a unique code (`PE100-DN110-PN10`); names are generated, so the 137 name variants collapse to one item per size and class.
6. **Approval with segregation of duties.** A supervisor cannot approve, reject or return a report they created or operated (administrators exempt). Every submission stores an immutable version.
7. **Go-live opening balances from a physical count,** posted through the Opening balances screen by an administrator; imported workbook history is read-only and excluded from live balances.

## Assumptions requiring confirmation

The defaults below are what the app does today. Items marked ⚙ are settings or master data an administrator can change without a new release.

| # | Question for PLASMACO | Default in the app |
|---|---|---|
| 1 | Which raw materials are tracked separately (PE100 grades, masterbatch, additives, packaging)? | One item, "HDPE PE100 resin (grade not specified)" ⚙ |
| 2 | Is 25 kg the bag weight for every delivery? | Bag weight per material; 25 kg pre-filled for resin ⚙ |
| 3 | Which extrusion lines, machines and shifts exist, with shift times? | None seeded; the administrator creates them ⚙ |
| 4 | How are production targets set (per line, product, shift; metres or kg)? | Target per entry or from the target table; achievement shows "No target" when absent ⚙ |
| 5 | Does target achievement use good or gross output? | Good output ÷ target |
| 6 | When does pipe enter finished-goods stock? | Storekeeper production receipt against an approved report |
| 7 | Are ISO 4427-2 weights the source of truth for kg/m? | ISO values; where the ISO sheet has no weight the stock sheet's value is used. Every difference is written in the product's note for review ⚙ |
| 8 | Do products have codes in another system (ERP, invoicing)? | Generated code, editable ⚙ |
| 9 | Are coils and straight lengths stocked separately? | Metres; coils/pieces only where a conversion is configured ⚙ |
| 10 | Real minimum and reorder levels? | Imported as found (mostly 0) and flagged; reorder point empty ⚙ |
| 11 | Is rejected pipe reground and returned to raw material? | Rejects and scrap recorded; no regrind flow until confirmed |
| 12 | Go-live cut-off and opening balances? | Physical count at go-live; workbook history imported read-only |
| 13 | May stock go negative in any case? | **Blocked.** Override off until enabled; Manager/Admin only, with a note ⚙ |
| 14 | Who approves reports; can managers reopen approved ones? | Supervisor approves, rejects or returns; Manager or Admin reopens with a reason |
| 15 | Which dispatch details are mandatory (customer, delivery note, vehicle)? | Optional reference and counterparty fields |
| 16 | Downtime causes and defect categories? | Empty lists; the administrator defines them ⚙ |
| 17 | Can PLASMACO share the daily production workbook linked from the stock file (`04th february 2026.xlsx`)? | Not supplied; no importer for it yet |
| 18 | Ideal run rate per product and line? | Performance and OEE show "Ideal run rate not configured" until set ⚙ |
| 19 | Where will the server run, and how do users sign in? | Self-hosted Spring Boot + PostgreSQL; local accounts with password/PIN and tokens |
| 20 | Should storekeepers export stock reports? | No: the role matrix gives storekeepers stock access but not the export permission. One line in `RolePermissions` changes it |
| 21 | Alert thresholds (achievement < 85 %, rejection > 5 %, downtime > 60 min per report, pending approval > 24 h, material variance > 10 %) | As listed; achievement, rejection and downtime are settings ⚙; the 24 h and 10 % thresholds are fixed in code |
| 22 | Working days for missing-report alerts | Monday–Saturday ⚙ |
| 23 | Session idle timeout on shared devices; offline sign-in window | 15 minutes ⚙; offline sign-in for 14 days after the last online sign-in |

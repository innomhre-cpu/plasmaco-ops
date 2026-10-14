# Moving from the stock workbook to the app

PLASMACO's current record is the macro-enabled workbook *Finished Goods & Raw Material Stock 2026* (one sheet per
month, one block per day, 132 pipe products in metres and one resin balance in kg). The app imports it as
**read-only history** and starts **live stock** from opening balances that an administrator confirms at go-live.
Nothing in the workbook is trusted blindly: every repair the importer makes is listed, and no imported figure changes
a live balance.

## 1. Before go-live

1. Agree the open questions in [decisions.md](decisions.md), at least: the raw materials to track, lines, machines and
   shifts, downtime and defect categories, minimum and reorder levels, and who approves reports.
2. Install the server ([server.md](server.md)) or decide to run one device standalone.
3. Sign in as the administrator and enter the master data (Administration → Master data). The 132 workbook
   products are created by the first import if they do not exist yet; review their kg/m and the notes on each one.

## 2. Import the history (Administration → Excel import)

1. Choose the newest workbook (`.xlsx` or `.xlsm`, at most 40 MB) with the system file picker.
2. Pick the month sheets to import (all by default) and the raw-material item that receives the workbook's single
   resin balance.
3. Read the preview before committing. It shows the date range, the number of finished-goods and raw-material
   movements, the reconciliations, products that would be created, and every issue with its sheet and cell.
4. Commit. The import is one transaction: all of it is stored or none of it. The file's SHA-256 is recorded, so the
   same file cannot be imported twice, and an audit entry records who imported what.

Importing a later save of the same workbook adds only days not imported before. A past value that changed between
saves is reported as a conflict and the first imported value is kept; correct the live ledger with an adjustment if
needed.

### What the importer does with the workbook's problems

| Workbook problem | What the import does |
|---|---|
| Dates with day and month swapped, dates stored as text, padded or duplicated dates, rising years in the resin block | Rebuilds the date from the sheet and the block order; logs each repair (`date.repaired`, `date.duplicate_fixed`, `date.inferred`) |
| An opening that differs from the previous closing; a closing that is not opening + in − out | Adds a labelled **legacy reconciliation** movement so the history reproduces the workbook's own figures and the jump stays visible (`fg.opening_jump`, `fg.closing_mismatch`) |
| Resin receipts typed into the opening cell (e.g. `=990*25`) | Imported as goods receipts, with the bag count kept (`rm.receipt_in_opening`) |
| `#VALUE!` cells, unchained balances, issues pointing at another file or missing | Logged; the reconciliations keep the resin history equal to the workbook's balance (`rm.error_value`, `rm.balance_unchained`, `rm.external_link`, `rm.issue_missing`) |
| Negative balances | Imported as history and flagged (`fg.negative`); they never reach live stock |
| Product names with inconsistent spelling | Matched to one product per DN/PN; names that cannot be parsed are reported (`product.unparsed`) |

Measured on the six supplied files (`LegacyWorkbookIntegrationTest`, run with `tools/verify-core.sh <folder>`): the
newest workbook gives 191 days (1 Feb – 7 Oct 2026), 24,420 product rows, 1,736 finished-goods and 167 raw-material
movements and 148 reconciliations; the imported resin balance equals the workbook's 58,300.60 kg. Re-importing a
different save adds 131 movements, skips 1,772 already imported and reports no conflict.

## 3. Go-live opening balances (Administration → Opening balances)

1. Choose the cut-off date (the first day of live stock).
2. Count the stock physically. The screen pre-fills each item with the imported history's closing on the day before
   the cut-off; replace it with the counted quantity wherever it differs (a value left untouched is posted as shown).
3. Give the source of the figures (for example "Physical count 31 Oct 2026, team A") and post. Opening balances can
   be posted once, cannot be negative, and are audited. Later corrections are adjustments with a reason and an
   approver.

From then on, finished goods enter stock only through production receipts against approved reports, and leave
through dispatches; resin moves through goods receipts, issues to production and returns.

## 4. Stop using the workbook

Keep the last workbook file read-only for reference. Running both in parallel for a short pilot is reasonable, but the
app's ledger is the record from the cut-off date.

## Not covered

- The daily production workbook referenced by the stock file (`04th february 2026.xlsx`) was not supplied, so there
  is no importer for it.
- The importer reads the stock-workbook layout only. Another layout is refused with an explanation rather than
  guessed.

# Workbook analysis scripts

The Python scripts used during discovery to read PLASMACO's stock workbooks and to generate seed code. They are kept
so the facts in `docs/decisions.md` and the generated Kotlin can be reproduced; the app does not use them.

Run with Python 3 and `openpyxl` (and `matplotlib` + `fontTools` for the font metrics), in a folder that holds the
workbook and the JSON files they read and write. Treat workbooks as untrusted input: run with `python3 -I`.

| Script | Purpose |
|---|---|
| `parse_wb.py <workbook.xlsm> <out.json>` | Normalises one workbook into JSON (sheets, day blocks, rows, formulas and cached values) |
| `iso_products.py` | Extracts the ISO 4427-2 weight table (`Rs iso 4427-2` sheet) into `iso.json` |
| `gen_seeds.py <iso.json> <wb.json> <out.kt>` | Generates `core/domain/.../master/Seeds.kt` (ISO table and the workbook's product list) |
| `gen_helvetica_metrics.py <out.kt>` | Generates `core/domain/.../export/pdf/HelveticaMetrics.kt` from the Adobe core-font metrics |
| `checks.py`, `explore*.py`, `monthly.py`, `versions.py` | The data-quality findings in the discovery report (dates, openings, totals, kg/m, differences between saves) |
| `vba_dump.py <file>` | Lists the VBA streams in a macro-enabled workbook (read only; nothing is executed) |

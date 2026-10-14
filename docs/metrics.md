# Metrics, dashboard and alerts

Every figure is computed from records in the app; none is typed. When a figure cannot be computed the app shows the
reason ("No target", "Ideal run rate not configured", "Not attributable to a single product") instead of a zero. The
definition of each metric appears beside it in the app and in exported reports. Source: `core/domain/.../metrics`
and `.../analytics`.

## Scope

- Analytics count **submitted and approved** reports by default; the filter "Approved only" narrows to approved.
  Drafts, returned and rejected reports never count.
- Filters: period (today, yesterday, last 7 days, this month, last month, custom range), line, product, shift.
- Output is in metres; quantities entered in another unit are converted with the product's configured conversions.
- Operators see only their own reports; stock figures need stock visibility (role matrix).

## Production metrics

| Metric | Unit | Definition |
|---|---|---|
| Gross output | m | Good output + rejected output |
| Good output | m | Output that passed inspection |
| Rejected output | m | Output rejected on the line or at inspection |
| Production target | m | Sum of entry targets; entries without a target are excluded from achievement |
| Target achievement | % | Good output ÷ target × 100, over entries that have a target |
| Target variance | m | Good output − target, over entries that have a target |
| Rejection rate | % | Rejected output ÷ gross output × 100 |
| Theoretical resin | kg | Σ gross metres × kg per metre from the product master (ISO 4427-2) |
| Material consumed | kg | Issued − returned + line-side opening − line-side closing |
| Material variance | kg, % | Resin consumed − theoretical resin; as % of theoretical |
| Scrap and purge | kg | Scrap recorded on material lines (part of consumption) |
| Downtime | min | Stoppage minutes, overlapping stoppages on the same machine merged |
| Unplanned downtime | min | Downtime in categories not marked as planned |
| Availability | % | (Planned time − unplanned downtime) ÷ planned time; planned time = report window − planned downtime |
| Performance | % | (Gross metres ÷ run hours) ÷ ideal metres per hour (needs a run rate for the line) |
| Quality rate | % | Good output ÷ gross output |
| OEE | % | Availability × performance × quality, approved reports only, when every factor is available |

## Dashboard KPIs (13)

Gross output · Production target · Target achievement · Good output · Rejected output · Downtime · Raw material
consumption · Finished goods receipts · Finished goods dispatches · Finished goods stock · Raw material stock ·
Pending approvals (submitted reports + adjustments awaiting approval) · Reorder alerts (items out of stock, below
minimum or at reorder point). Stock KPIs are live balances; receipts and dispatches are posted movements in the period.

## Charts (10, Analytics screen)

| Chart | Shows | Tapping a bar opens |
|---|---|---|
| Daily good output vs target | Good output per day with the target line | That day's reports |
| Target achievement by product (lowest first) | Achievement % per product against 100 % | The product's reports |
| Target achievement by shift | Achievement % per shift against 100 % | The shift's reports |
| Good output by product | Top products, the rest folded into "Other" | The product's reports |
| Good output by line | Good output per line | The line's reports |
| Good vs rejected output | Good and rejected metres per day | That day's reports |
| Raw material consumed vs theoretical | Resin consumed and theoretical resin per day | That day's reports |
| Downtime by machine and cause | Minutes per machine, stacked by category | The downtime report |
| Finished-goods movements | Receipts above the axis, dispatches below, per day | That day's reports |
| Raw-material movements | Receipts and returns above, issues below, per day | That day's reports |

The inventory charts appear only for roles with stock visibility. Multi-series charts use five fixed categorical
colours (validated for colour-vision deficiency); a sixth series folds into "Other". Every chart has a legend or
direct label, touch tooltips and a text summary for screen readers.

## Alerts

| Alert | Fires when | Severity | Opens |
|---|---|---|---|
| Stock | An item is out of stock or below its minimum | Critical | Item stock card |
| Stock | An item is at or below its reorder point | Warning | Item stock card |
| Below target | A report's target achievement is below the minimum (default 85 %) | Warning | The report |
| High rejection | A report's rejection rate is above the maximum (default 5 %) | Warning | The report |
| Downtime | A report's downtime exceeds the maximum (default 60 min) | Warning | The report |
| Material variance | Resin variance on a report is beyond ±10 % of theoretical | Info | The report |
| Missing report | No report for an active line and shift on a working day (default Monday–Saturday) | Warning | That day's reports |
| Pending approval | A submitted report waits for a decision (warning after 24 h) | Info, then warning | The report |
| Changes not synced | Device changes failed or conflict with the server | Warning | Settings → Sync |

Alerts are computed for the last 14 days from what the signed-in person may see, appear on the dashboard and in
Notifications (unread count on the bell) and, when the app has permission, as Android notifications. An alert is
raised once while its condition lasts, resolves itself when the condition clears, and is raised again if it returns.

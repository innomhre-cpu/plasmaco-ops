# User guide

PLASMACO Operations records each shift's production, keeps the stock ledger for finished pipe and raw materials,
routes reports for approval and turns all of it into dashboards and printable reports. It works without a
connection; changes are sent to the server when the device is online.

What you see depends on your role:

| Role | Main tasks |
|---|---|
| Production Operator | Daily production reports for their shifts; their own history |
| Storekeeper | Receipts, issues, dispatches, transfers, stock counts, adjustment requests |
| Production Supervisor | Reports, approvals, analytics, stock view, exports, adjustment approval |
| Manager | Dashboards, analytics, exports, reopening approved reports, adjustment approval, audit log |
| Administrator | Everything above, plus master data, users, import, opening balances, settings and backups |

## Signing in

Enter your username and PIN or password. With a server, the first sign-in on a device must be online; after that you
can sign in offline for 14 days. Shared devices sign you out after 15 minutes without use (an administrator can
change this). Five wrong attempts lock the account on the server for 15 minutes.

## Daily production report (Production → New daily report)

1. **Identification** — date, shift, line, operator, start and end time. Night shifts that end after midnight are
   handled; times after midnight belong to the next day.
2. **Production** — one entry per product: machine, target, gross, good and rejected quantity (metres, or a
   configured unit such as coils), batch and times.
3. **Materials** — resin and other materials issued, returned, line-side opening and closing, scrap. Bags convert to
   kg with the material's bag weight. The theoretical resin for the output is shown for comparison.
4. **Downtime** — each stoppage with machine, start, end, category, reason and corrective action. An open stoppage
   can be closed later.
5. **Quality** — inspections with sample, accepted, rejected, defect category and result.
6. **Handover** — notes for the next shift.
7. **Review** — everything on one page with the checks that still fail.

**Save draft** keeps your work; **Save and continue** saves and moves to the next step; **Preview** shows the report
as it will print; **Submit** sends it for approval once every check passes. A submitted report is locked. If the
supervisor returns it, correct it and submit again; each submission is kept as a numbered version.

## Approvals (Approvals, supervisors)

Open a submitted report and choose **Approve**, **Return for correction** (with a reason) or **Reject** (with a
reason). You cannot decide on a report you created or operated. Managers and administrators can **Reopen** an
approved report with a reason. The report shows its approval history, versions (with what changed between them)
and audit trail.

## History

Search and filter reports by date, product, line, shift and status. Operators see their own reports. **Duplicate**
starts a new draft from an existing report; a draft never submitted can be deleted.

## Inventory (storekeepers)

- Finished goods and raw materials have separate tabs; each item opens its stock card (movements and running
  balance).
- **Movements:** goods received, issue to production, return from production (raw materials); production receipt,
  dispatch, customer return (finished goods); transfers in and out. A production receipt is posted against an
  approved report entry and cannot exceed its good output — recording production does not add stock by itself.
- **Adjustments and scrap write-offs** need a reason and wait for a supervisor or manager to approve them.
- **Stock counts:** record counted quantities; the differences become count-variance movements for approval.
- Stock cannot go below zero. If management enables the audited override, a manager or administrator can post a
  movement that does, with a note explaining why.

## Dashboard and analytics

The dashboard shows the current shift, 13 KPIs and the open alerts for the chosen period and filters. Analytics
adds ten charts. Tap a KPI, a bar or an alert to open the records behind it. Every figure's definition is shown
beside it ([metrics.md](metrics.md)).

## Reports and export (Reports)

Choose one of the 11 reports — daily production, shift production, production performance, raw material
consumption, raw material stock, finished goods stock, inventory movement, quality control, downtime, production and
material variance, management summary — set the period and filters, preview it, then **Save as PDF**, **Save as
Excel** (you pick where the file goes) or **Share**. Any production report you can view can also be saved or
shared as a PDF from its own screen.

## Notifications

The bell shows unread alerts: low stock, missed targets, high rejection, long downtime, material variance, missing
reports, pending approvals and changes that could not be synced. Each opens the record concerned.

## Sync (Settings → Sync)

The top bar shows whether you are online and how many changes are waiting. Sync runs automatically; **Sync now**
forces it. If someone else changed the same record, the conflict is shown side by side: **Use server copy** or
**Keep mine** (applies your change on top of the server's version). Changes the server refused are listed with the
reason; **Retry failed** sends them again after you have fixed the cause.

## Administration

- **Master data:** products (DN, PN, kg/m, units and conversions), raw materials, lines, machines, shifts, downtime
  and defect categories, stock thresholds, production targets, run rates. Records are deactivated, never deleted.
- **Users and roles:** create accounts, set roles and PINs, deactivate. With a server this needs a connection.
- **Excel import** and **Opening balances:** see [data-migration.md](data-migration.md).
- **Audit log:** every change with who, when and what.
- **Backup and restore:** copies this device's database to a file you choose; restore takes effect at the next
  start. Backups contain business data and PIN hashes: keep them somewhere access-controlled.
- **Settings:** server address (HTTPS), alert thresholds, working days, idle timeout, theme, negative-stock override.

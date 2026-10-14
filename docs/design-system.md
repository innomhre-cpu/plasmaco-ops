# Design system

The app follows the supplied Testora references (three template images: home, analytics and student screens): a teal
identity on a light grey canvas, flat white cards with large radii, circular icon buttons, pill chips, and a
teal hero card. It keeps that language and adapts it to production data entry, adds the states the template never
shows (empty, loading, error, offline, validation, dark theme), and darkens some text colours so they pass WCAG AA.

All values live in `app/src/main/kotlin/rw/plasmaco/ops/ui/theme` (`Color.kt`, `Type.kt`, `Theme.kt`) and are read
through `Plasmaco.colors`, `Plasmaco.type`, `Plasmaco.shapes` and `Plasmaco.spacing`. Screens never hard-code colours.

**Fidelity statement.** Colours, radii and component shapes were taken from the reference images by measurement and
are applied consistently; the screens have not been compared side by side with the template on a device, so no
pixel-level fidelity is claimed. Layouts differ where the content requires it (forms, tables, approval actions).

## Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| primary | `#17504E` | `#1F6A67` | Buttons, hero cards, selection |
| primaryTint | `#E3EEED` | `#183634` | Selected chips, icon badges |
| accent (graphics only) | `#00C953` | `#2EDB7A` | Live dots, ring progress |
| background | `#F1F2F4` | `#0E1313` | Screen canvas |
| surface | `#FFFFFF` | `#161D1D` | Cards |
| surfaceInset | `#F5F7F7` | `#1E2726` | Nested cards, table bands |
| border | `#E6E9EA` | `#2A3534` | Inputs, chips, dividers |
| textPrimary | `#111414` | `#E8EEED` | Titles, values |
| textSecondary | `#626B6B` | `#A3B0AF` | Labels (template `#838383` was 3.8:1; this is 5.5:1) |
| success | `#0B7F3D` | `#2EDB7A` | Approved, positive deltas |
| warning text / graphic | `#8A5A00` / `#EFB201` | `#F5C542` | Pending, low stock |
| danger text / graphic | `#C0362C` / `#E5484D` | `#FF7A72` | Rejections, out of stock, errors |
| info | `#2A5F94` | `#8DB8E8` | Sync and informational notices |

Status always carries a text label; colour is never the only signal.

**Charts.** Single-series charts use the template treatment: sage context bars `#C9D3D2`, the selected or current
bar in primary teal, a teal tooltip pill. Multi-series charts use five categorical colours in fixed order — teal
`#00876F`, amber `#C47D04`, blue `#2A75BA`, coral `#D45B3D`, violet `#795BBF` — validated for colour-vision
deficiency on both themes; a sixth series folds into "Other". Heatmaps use a six-step teal ramp from `#E3EAE9` to
`#17504E`. Status colours never double as series colours.

## Type

Inter, bundled in the APK (SIL Open Font Licence, `docs/licenses/Inter-OFL.txt`), with tabular figures for numbers.

| Token | Size / line (sp) | Weight |
|---|---|---|
| screenTitle | 22 / 28 | SemiBold |
| sectionTitle | 18 / 24 | Medium |
| cardTitle | 16 / 22 | Medium |
| kpiValue | 28 / 34 | Medium, tabular |
| body | 14 / 20 | Regular |
| bodySmall | 13 / 18 | Regular |
| label | 12 / 16 | Regular |
| caption / axis | 11 / 14 | Regular |
| button | 15 / 20 | Medium |

## Shape, spacing, size

- Radii (dp): card 20 · inset card 16 · button 14 · small button 10 · input 12 · chip 10 · badge 6 · tooltip 8 ·
  bar end 4; icon buttons circular.
- Spacing (dp): 2 · 4 · 8 · 12 · 16 · 20 · 24 · 32; screen gutter 16; card padding 16 (header cards 20); card gap 12.
- Touch targets at least 48 dp; buttons 48 dp high; inputs 52 dp; top bar 64 dp; bottom bar 72 dp.
- Cards are flat (no elevation), as in the template; sheets, dialogs and menus use tonal elevation.
- Icons: Material Symbols Outlined (closest to the template's line icons).

## Components (`ui/components`)

| Component | From the template | Notes |
|---|---|---|
| Top bar with logo, bell, sync pill | Hamburger + logo + circular icons | Bell shows unread count; sync pill replaces the chat icon |
| `PageHeaderCard`, `HeroStatusCard`, `RingProgress` | Greeting card, live exam card | Current shift with target achievement ring |
| `KpiCard`, `DeltaText`, `IconBadge` | Ongoing exam / enrolment tiles | "No data" state with the reason |
| `SectionCard`, `ArrowButton`, `TextLink` | Section cards with ↗, "See all" | Drill-down to detail screens |
| `PrimaryButton`, `SecondaryButton`, `SmallButton`, `CircleIconButton` | Buttons | Loading and disabled states |
| `SearchField`, `FilterChipRow`, `AppFilterChip`, `DropdownChip`, `DateRangeDialog` | Search, MCQ chips, "Monthly" | Material date-range picker |
| `AppTextField`, `NumericField`, `DateField`, `TimeField`, `FieldLabel`, `FieldError`, `FieldHint` | Not in the template | Label above, inline error, unit suffix |
| `StatusBadge`, `OutlineBadge`, `PriorityText` | Crisis/Action chips, Class badge, Urgent/High | Text always present |
| `ChartView`, `SegmentedGauge`, `StripeShareBar`, `SplitProgressBar`, `HeatmapGrid`, `LegendItem` | Template charts | Touch tooltips, legends, screen-reader summary, "No data" |
| `EmptyState`, `ErrorState`, `LoadingState`, `OfflineBanner`, `NoticeBanner`, `SyncStatusPill` | Not in the template | Card styling |
| `ConfirmDialog`, `ReasonDialog`, `AppBottomSheet` | Not in the template | 20 dp rounded surfaces |

## Layout

- Phones: bottom navigation (Dashboard, Production, History, Inventory, Reports — each shown only to roles that can
  use it), a drawer for the rest.
- Tablets and landscape: a navigation rail with a "New daily report" action; lists and forms use the extra width.
- Dark theme follows the system or the choice in Settings.
- Accessibility: content descriptions on icon-only buttons, semantic headings, live regions for errors and sync
  state, 48 dp touch targets, and charts summarised as text for screen readers. Behaviour at the largest system font
  sizes and with TalkBack has not yet been checked on a device.

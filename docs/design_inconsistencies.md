# Design Inconsistencies — running list (theming migration)

Pre-existing design contradictions noticed while migrating screens to the theme
system. **Not fixed during migration** — light mode is reproduced as-is, slot by
slot. Recorded here so they can be decided consciously at the end instead of being
inherited silently.

Format: what contradicts what, where, and which slots are involved.

1. **Back-arrow color differs per screen.** Settings tints the top-bar back arrow
   `colorScheme.primary` (brand green); SupportScreen and FaqScreen tint it
   `detoxColors.label` (black/white). Same navigation affordance, two colors.

2. **Two grays for the same "affordance icon" role.** Settings/FAQ chevrons use
   `detoxColors.hint` (#C7C7CC); SupportScreen's dropdown arrow uses
   `detoxColors.subtext` (#8E8E93). Both are "muted icon pointing at an action".

3. **History list vs. detail use different screen backgrounds.** HistoryScreen sits
   on `colorScheme.background` (white in light) with border-less white cards — cards
   are distinguishable only by corner radius/spacing; HistoryDetailScreen sits on
   `detoxColors.screenBackground` (#F2F2F7) with 0.5dp-bordered white cards. Same
   feature, two shell treatments.

4. **Top-bar title/back-arrow color varies again in History.** HistoryScreen leaves
   both on the TopAppBar default (`onSurface`, #1A1A1A light); HistoryDetail sets the
   title to `detoxColors.label` (#000000) but leaves the arrow on the default.
   Extends inconsistency 1 (Settings: green arrow; Support/FAQ: label-black arrow).

## Deliberate consolidations (approved light-mode changes)

These are conscious, user-approved visible changes made during migration — the design
previously used near-duplicate values for one role.

- **TimeSpinnerPicker selection highlight** #E8F8EF → `selectedSurface` (#F0FDF4).
  One selected-option tint across the flow instead of two near-identical pale greens.
- **Radio rings / inactive control outlines** #D1D1D6 → `colorScheme.outlineVariant`
  (#E0E0E5). ChallengeCreation mode/limit radio circles; Welcome's pager dots should
  follow when that screen migrates.
- **Fee-row label** #333333 → `label` (#000000). Hard-mode fee breakdown rows.
- (Earlier approvals, applied in later batches: #2E7D32/#E8F5E9 → softGreen pair,
  #E65100/#FFF3E0 → softOrange pair, ChallengeCard group-#5C6BC0 → groupAccent.)

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

5. **Hard-abandon and soft-abandon dialogs use OPPOSITE button patterns** (same
   screen, ActiveChallengeScreen). Hard-mode abandon: destructive "Ja, aufgeben" is a
   muted gray TextButton, safe "Nein, weitermachen" is a filled GREEN button
   (solidGreenBg) — deliberately emphasizing "keep going" because money is at stake.
   Soft-mode abandon: destructive "Ja" is a filled RED button, safe "Nein" is a plain
   TextButton — the app's standard destructive pattern. Both are intentional (the hard
   one discourages losing money), but two abandon dialogs on one screen reading
   oppositely is a real design question to settle at the end. Ruled: KEEP both.

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

### Batch 7 (app shell) — loud visible changes

- **[A] Dashboard permission-warning banner** `#FF3B30 → #D32F2F`
  (`DetoxAlertColors.Red`). Folds onto the same design-fixed alarm red as MainScreen's
  banner — two permission alarms with two different reds was itself the bug; they are
  now identical. Chosen over `danger` because `danger` lightens to #FF6B6B in dark and
  an alarm must never soften.
- **[D] App-icon / favicon / "+X" overflow fallbacks → `avatarFallbackBg` + `onSolid`.**
  ChallengeCard's app-icon fallback was `#F2F2F7` bg + gray letter — a third
  monogram-fallback treatment. Unified onto the one fallback role (muted `#AEAEB2` bg +
  white letter), matching HistoryDetail. **This is visible on the Dashboard — the app's
  first screen** — grey monogram circles become the muted avatar treatment.
- **ChallengeCard mode badges** now use the `solid*` family: soft `#2E7D32` →
  `solidGreenBg`, HARD `#B71C1C` → `DetoxAlertColors.RedDeep`, group `#5C6BC0` →
  `solidPurpleBg` (badge) / `groupAccent` (text+icon); "ends today" `#E65100` →
  `solidOrangeBg`. All white text → `onSolid`.

## Value-twin sweep (holder audit)

Same-value-in-both-modes pairs found. Most are **deliberate role separations** kept
apart so they can diverge (the established tileGreen-vs-accent principle) or are
cross-layer (a `*Bg` and a `*Fg`/`*Icon` sharing a hue, which the layer invariant keeps
separate). Left as-is; recorded for conscious review:
- Cross-layer / decorative-vs-meaning (intentional): softGreenIcon=tileGreen=accent=
  success (brand green); softOrangeIcon=warning; tileOrange=warningStrong;
  softPurpleText=tilePurple; softBlueIcon=tileBlue; softPurpleIcon=groupAccent;
  tileRed=danger; subtext=tileNeutral.
- Same-layer background twins worth a later look: **screenBackground = dialogSurface**
  (F2F2F7/0F0F0F) — dialogSurface was kept distinct intentionally (may diverge).
- Resolved this batch: the would-be `onSolid` vs `avatarFallbackFg` white twin — merged
  into a single `onSolid` rather than creating a second white `*Fg` slot.

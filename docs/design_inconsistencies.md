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

6. **Welcome feature badges: purple uses vivid text, green/orange use muted text.**
   The three onboarding feature badges are all `soft*Bg` containers, but the green and
   orange badges color their label with the muted `soft*Text` (#1E7A3C / #C05A00) while
   the purple badge uses the vivid `softPurpleIcon` (#7B61FF, the icon-glyph color).
   Migrated as-is (value-preserving); the purple badge just reads louder than its peers.

7. **Two "inactive dot" grays in Welcome.** The bottom pager dots use #D1D1D6 (mapped to
   `outlineVariant`); the page-4 stat-cycle dots use #C7C7CC (mapped to `hint`). Same
   "inactive indicator" role, two grays. Also: the Profile row chevron uses #8E8E93
   (`subtext`) where Settings/FAQ chevrons use #C7C7CC (`hint`) — extends inconsistency 2.

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

### Batch 9 (Friends + Groups + System screens) — new design-fixed sets & decisions

Final coverage batch. **Zero new holder slots** — every mapping reused existing slots.
Two new *design-fixed identity* constant sets were introduced (in `ui/theme/IdentityColors.kt`),
the same mechanism as `DetoxAlertColors` / `DetoxCelebrationColors`:

- **`DetoxAvatarPalette`** (pre-approved in the running plan) — the 6-color avatar hash
  palette (#5C6BC0/#42A5F5/#26A69A/#EC407A/#AB47BC/#26C6DA) from GroupChallengeDetail's
  `AvatarCircle`. Identity-distinguishing, theme-independent, order is load-bearing. NO recolor.
  Monogram letter uses `onSolid`.
- **`DetoxPodiumColors`** (**RATIFIED** — added to the documented exemption list) —
  Gold/Silver/Bronze (#FFD700/#C0C0C0/#CD7F32) for the leaderboard rank medals. Identity-
  carrying like the avatar palette (the color IS the meaning — rank 1/2/3), must not shift
  with the theme, no meaningful dark variant. Same reasoning as `DetoxAvatarPalette` /
  `DetoxAlertColors`. The frozen `GroupChallengeResultsScreen` podium keeps its OWN private
  copies under the overlay freeze — not refactored.

### Documented literal-exemption list (canonical, post-Batch-9)

A raw `Color(0x…)` in `presentation/` is a bug UNLESS it is one of:
`DetoxAlertColors`, `DetoxCelebrationColors`, `DetoxAvatarPalette`, **`DetoxPodiumColors`**,
the `BuildConfig.DEBUG` panels (ProfileScreen developer section), the frozen always-dark
overlays, and the `DetoxHorizontalPicker` dark/overlay-treatment palette (the `darkMode`
render branch — shared by `BudgetSelectionOverlay` and the in-app dark *style*; its light/
in-app branch is fully theme-resolved).

### Batch 9 cleanup — the two dark-mode defects (fixed, not deferred)

Both were "screen doesn't follow dark mode" — the exact defect class this refactor removes:

- **`DetoxHorizontalPicker` in-app branch** was rendering its neighbour-dimming ramp from
  hardcoded light grays (#000/#AAA/#CCC/#E0E0E0), so wizards (which always pass
  `darkMode = false`) showed black-on-dark in dark mode. Fixed by resolving the ramp from
  the theme: selected value → `label`, unit → `subtext`, centre-dot → `accent`, and the
  neighbour fade = `lerp(label, cardBackground, t)` for the original t-factors (170/204/224
  ÷255). On a white surface that lerp reproduces the exact original grays (light pixel-
  identical); in dark it fades white → #1A1A1A (readable). The `surfaceColor` default
  `Color.White` → `cardBackground` (fixes default-surface callers like GroupCreate/Active
  in dark; #FFFFFF in light, unchanged). **The `darkMode = true` render path is byte-for-byte
  untouched → the frozen `BudgetSelectionOverlay` is pixel-identical.**
- **ProfileScreen payout/Guthaben card** (`PayoutChallengeCard`, money-gated) still had
  #00C853/#000000/#8E8E93/`Color.White`. Migrated colors only (money-floor gating untouched):
  card → `cardBackground`; "Abgeschlossen" badge → `solidGreenBg` + `onSolid` (white-text
  status badge; **visible light shift #00C853→#2E7D32** — the solid-badge family keeps white
  legible in both modes, a logged legibility exception, same family as the Batch-7
  ChallengeCard badges); refunded status → `success`; date/labels → `label`/`subtext`.

Approved-style consolidations applied this batch (visible, minor):
- **Leaderboard own-row** #F9FFF9 → `selectedSurface` (#F0FDF4 light / #12291B dark). The
  raw near-white had no dark value; `selectedSurface` gives the correct green-tinted dark row.
- **Group LIVE / COMPLETED status badge** #E8F5E9 bg + #2E7D32 text → `softGreenBg` /
  `softGreenText` (same #E8F5E9/#2E7D32→softGreen consolidation used for FriendsHub's LiveBadge).
  Now matches the canonical design-system "Group LIVE" badge (#E8F8EF/#1E7A3C).
- **Group CANCELLED badge** bg #FFEBEE → `colorScheme.errorContainer` (text stays
  `colorScheme.error`). Imperceptible light shift, correct dark rendering.
- **Fee-row / summary greens** split by meaning: refund value → `success`, pot highlight → `accent`.
- **DuBadge** kept value-preserving: `accent.copy(alpha=0.15f)` bg + `accent` text (NOT migrated to
  the canonical softGreen "Du badge" pair — see new inconsistency 10 below).

Judgment call logged (nearest-meaning, no new slot):
- **WAITING status badge** neutral bg #F5F5F5 → `insetSurface` + `subtext`. The holder has no
  pale-neutral badge background; a neutral chip reads as a recessed neutral fill, so it folds
  onto `insetSurface`. If a distinct "neutral chip" role is wanted later, that's the one slot the
  group screens might argue for — flagged, not added.

New pre-existing inconsistencies noticed (migrated as-is, decide later):

8. **Group screen shells disagree on background.** FriendsHub sits on
   `colorScheme.background` (white light) with elevated (2dp) borderless cards; GroupChallengeCreate
   /Detail sit on `screenBackground` (#F2F2F7) with 0.5dp-bordered flat white cards. Same feature
   family, two shell treatments — extends inconsistency 3 (History list vs detail).

9. **Three "waiting" treatments.** FriendsHub's WaitingBadge is a green (`colorScheme.primary`)
   bordered pill; GroupChallengeDetail's WAITING status badge is a neutral grey pill
   (`insetSurface`/`subtext`); the design-system doc specs Group WAIT as grey (#F2F2F7/#8E8E93).
   The two code paths render "waiting" differently.

10. **DuBadge vs canonical "Du badge".** GroupChallengeDetail's DuBadge is `accent`@15% bg +
    `accent` text (translucent green); the design-system doc's "Du badge" is the opaque softGreen
    pair (#E8F8EF/#1E7A3C). Kept value-preserving; two green-badge idioms for the same concept.

11. **Group limit-card unchecked ring is darker than the solo wizard's.** GroupChallengeCreate's
    limit-type radio ring outlines with `subtext` (#8E8E93); the solo `ChallengeCreationScreen`
    uses `colorScheme.outlineVariant` (#E0E0E5) for the same unchecked ring. Preserved as-is.

12. **Destructive-confirm red is spelled three ways on GroupChallengeDetail.** Quit dialog confirm =
    `colorScheme.error`; leave/delete dialog confirms = `danger` (ex-`AbandonRed`). They resolve to
    the same value in both modes, but the same role is written two ways on one screen.

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

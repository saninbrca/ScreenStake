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

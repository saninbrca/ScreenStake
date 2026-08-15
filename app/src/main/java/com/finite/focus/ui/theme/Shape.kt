package com.finite.focus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// NOTE: every entry below uses the RoundedCornerShape(Int) overload, which is a PERCENT of the
// shorter side — NOT dp. `extraLarge` is therefore 32 % , and M3 resolves an AlertDialog's default
// shape to `shapes.extraLarge`, which is what made our dialogs look over-rounded. Left as-is here
// because these values are load-bearing for other surfaces; dialogs opt out via [DetoxDialogShape].
val DetoxShapes = Shapes(
    extraSmall = RoundedCornerShape(4),
    small = RoundedCornerShape(8),
    medium = RoundedCornerShape(16),
    large = RoundedCornerShape(24),
    extraLarge = RoundedCornerShape(32)
)

/** The standard card corner radius (matches the 16.dp used by cards across the app). */
val DetoxCardRadius = 16.dp

/** Corner shape for our OWN dialogs, so they match cards instead of M3's 32 % default. */
val DetoxDialogShape = RoundedCornerShape(DetoxCardRadius)

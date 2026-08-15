package com.finite.focus.presentation.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finite.focus.R
import com.finite.focus.ui.theme.DetoxCardRadius
import com.finite.focus.ui.theme.detoxColors

/**
 * One step of a permission how-to: a short line of text plus the screenshot that shows it.
 *
 * [imageRes] is only ever a resource *name*. Swapping the picture for a step means dropping a new
 * `help_<permission>_step_<n>.webp` into `res/drawable/` over the placeholder of the same name —
 * no Kotlin change, no recomposition of this list.
 */
data class PermissionHelpStep(
    @StringRes val textRes: Int,
    @DrawableRes val imageRes: Int,
)

/**
 * The step lists themselves. Kept as data so a screen only picks a topic; adding, reordering or
 * removing a step is a one-line edit here rather than a change to the sheet's layout.
 */
object PermissionHelpTopics {

    val Overlay: List<PermissionHelpStep> = listOf(
        PermissionHelpStep(R.string.permission_help_overlay_step_1, R.drawable.help_overlay_step_1),
        PermissionHelpStep(R.string.permission_help_overlay_step_2, R.drawable.help_overlay_step_2),
        PermissionHelpStep(R.string.permission_help_overlay_step_3, R.drawable.help_overlay_step_3),
    )

    val Accessibility: List<PermissionHelpStep> = listOf(
        PermissionHelpStep(R.string.permission_help_accessibility_step_1, R.drawable.help_accessibility_step_1),
        PermissionHelpStep(R.string.permission_help_accessibility_step_2, R.drawable.help_accessibility_step_2),
        PermissionHelpStep(R.string.permission_help_accessibility_step_3, R.drawable.help_accessibility_step_3),
    )

    val UsageStats: List<PermissionHelpStep> = listOf(
        PermissionHelpStep(R.string.permission_help_usage_step_1, R.drawable.help_usage_step_1),
        PermissionHelpStep(R.string.permission_help_usage_step_2, R.drawable.help_usage_step_2),
        PermissionHelpStep(R.string.permission_help_usage_step_3, R.drawable.help_usage_step_3),
    )

    /** Everything the onboarding permissions page asks for, in the order the page lists it. */
    val AllPermissions: List<PermissionHelpStep> = Overlay + Accessibility + UsageStats
}

/**
 * Read-only help sheet: an ordered list of [steps], each rendered as "number → text → screenshot".
 *
 * Purely explanatory — it never requests a permission and never launches a settings intent, so it
 * cannot affect the permission gate. Dismissing it is the only way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionHelpSheet(
    steps: List<PermissionHelpStep>,
    onDismiss: () -> Unit,
    @StringRes titleRes: Int = R.string.permission_help_title,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = detoxColors.label,
            )

            Text(
                text = stringResource(R.string.permission_help_subtitle),
                fontSize = 13.sp,
                color = detoxColors.subtext,
            )

            steps.forEachIndexed { index, step ->
                PermissionHelpStepRow(index = index, step = step)
            }
        }
    }
}

@Composable
private fun PermissionHelpStepRow(index: Int, step: PermissionHelpStep) {
    val stepNumber = index + 1

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stepNumber.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = stringResource(step.textRes),
                fontSize = 14.sp,
                color = detoxColors.label,
                modifier = Modifier.weight(1f),
            )
        }

        // Image slot. Sized by the drawable itself so a real screenshot drops in unchanged.
        Image(
            painter = painterResource(step.imageRes),
            contentDescription = stringResource(R.string.permission_help_step_image_cd, stepNumber),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DetoxCardRadius)),
        )
    }
}

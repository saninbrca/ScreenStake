package com.detox.app.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.ui.theme.DetoxGrayedOut
import com.detox.app.ui.theme.DetoxTrackableGreen

@Composable
fun AppUsageCard(
    appUsageInfo: AppUsageInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = if (appUsageInfo.isTrackable) 1f else 0.5f
    val context = LocalContext.current

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        enabled = appUsageInfo.isTrackable,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            appUsageInfo.icon?.let { drawable ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(drawable)
                        .size(144)
                        .build(),
                    contentDescription = appUsageInfo.appName,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appUsageInfo.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (appUsageInfo.isTrackable) DetoxTrackableGreen.copy(alpha = 0.15f)
                else DetoxGrayedOut.copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (appUsageInfo.isTrackable) stringResource(R.string.app_selection_trackable)
                    else stringResource(R.string.app_selection_not_trackable),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (appUsageInfo.isTrackable) DetoxTrackableGreen else DetoxGrayedOut,
                    maxLines = 1
                )
            }
        }
    }
}

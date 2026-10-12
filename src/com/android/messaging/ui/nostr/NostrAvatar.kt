package com.android.messaging.ui.nostr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.nostr.nostrEntryPoint

/**
 * A round avatar for a Nostr contact or group. Shows the profile picture only when
 * "Show profile pictures" is on; otherwise (and while loading) the first letter of the name.
 */
@Composable
internal fun NostrAvatar(
    name: String,
    pictureUrl: String?,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    size: Dp = 48.dp,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) { context.nostrEntryPoint() }
    val loadPictures by entryPoint.nostrAccountStore().loadProfilePictures
        .collectAsStateWithLifecycle()

    val picture by produceState<ImageBitmap?>(initialValue = null, pictureUrl, loadPictures) {
        value = if (loadPictures && pictureUrl != null && !isGroup) {
            entryPoint.nostrMedia().loadAvatar(pictureUrl)?.asImageBitmap()
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = picture
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )

            isGroup -> Icon(
                imageVector = Icons.Rounded.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            else -> Text(
                text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = if (size >= 40.dp) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

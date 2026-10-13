package com.android.messaging.ui.twill

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
import com.android.messaging.twill.TwillAvatar
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.twillEntryPoint

/**
 * A round avatar: the protocol's picture for [avatar] when it has one (each protocol decides,
 * e.g. Nostr only with "Show profile pictures" on), otherwise the first letter of the name.
 */
@Composable
internal fun TwillAvatarImage(
    avatar: TwillAvatar,
    protocol: TwillProtocolId,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val context = LocalContext.current
    val protocols = remember(context) { context.twillEntryPoint().twillProtocols() }
    val picture by produceState<ImageBitmap?>(initialValue = null, avatar.ref, protocol) {
        value = avatar.ref?.let { ref -> protocols[protocol].loadAvatar(ref)?.asImageBitmap() }
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

            avatar.isGroup -> Icon(
                imageVector = Icons.Rounded.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            else -> Text(
                text = avatar.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
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

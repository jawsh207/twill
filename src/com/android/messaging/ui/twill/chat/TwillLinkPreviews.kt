package com.android.messaging.ui.twill.chat

import android.util.Patterns
import android.webkit.URLUtil
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.messaging.util.YouTubeUtil

/**
 * Which link previews Nostr and Delta Chat chats show, from SMS's settings ("Website link
 * previews" and "YouTube link previews", both off by default because fetching a preview
 * visits the site).
 */
@Immutable
internal data class TwillLinkPreviews(
    val websites: Boolean = false,
    val youTube: Boolean = false,
)

/** The open chat's link preview settings. */
internal val LocalTwillLinkPreviews = staticCompositionLocalOf { TwillLinkPreviews() }

/**
 * The first link in [text] worth a preview card under the message, as SMS picks it: the first
 * web address, if previews for that kind of link (YouTube or other sites) are on.
 */
internal fun twillPreviewUrl(text: String, previews: TwillLinkPreviews): String? {
    if (!previews.websites && !previews.youTube) return null
    val matcher = Patterns.WEB_URL.matcher(text)
    while (matcher.find()) {
        val url = URLUtil.guessUrl(matcher.group() ?: continue)
        val isWeb = url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)
        if (!isWeb) continue
        val isYouTube = YouTubeUtil.getYoutubePreviewImageLink(url) != null
        return url.takeIf { if (isYouTube) previews.youTube else previews.websites }
    }
    return null
}

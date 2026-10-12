package com.android.messaging.ui.conversation.messages.ui.link

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.util.LogUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whether website link previews are enabled for the conversation being shown. Provided by the
 * conversation screen from the "Website link previews" privacy setting (off by default).
 */
internal val LocalWebLinkPreviewsEnabled = staticCompositionLocalOf { false }

@Immutable
internal data class WebLinkPreviewData(
    val url: String,
    val host: String,
    val title: String?,
    val description: String?,
    val image: Bitmap?,
) {
    val hasContent: Boolean
        get() = !title.isNullOrBlank() || !description.isNullOrBlank() || image != null
}

/**
 * Card shown under a message's text with the page title, description and preview image.
 * Renders nothing until the page metadata has loaded, and nothing at all if the page has none.
 */
@Composable
internal fun WebLinkPreviewCard(
    url: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview by produceState<WebLinkPreviewData?>(initialValue = null, url) {
        value = WebLinkPreviewLoader.load(url)
    }

    val data = preview?.takeIf { it.hasContent } ?: return
    val shape = RoundedCornerShape(size = 12.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onOpen(data.url) },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            data.image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio = PREVIEW_IMAGE_ASPECT_RATIO),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                data.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                data.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = data.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val PREVIEW_IMAGE_ASPECT_RATIO = 1.91f

/**
 * Fetches Open Graph / HTML metadata for a page over HTTPS only, with tight size and time
 * limits. Results (including failures) are cached in memory for the life of the process so a
 * conversation never refetches the same link while scrolling.
 */
internal object WebLinkPreviewLoader {
    private const val TAG = "WebLinkPreview"
    private const val CACHE_SIZE = 64
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 8_000
    private const val MAX_HTML_BYTES = 512 * 1024
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val TARGET_IMAGE_WIDTH = 720
    private const val MAX_REDIRECTS = 4

    // Generic UA so sites serve their normal HTML with OG tags; nothing identifying is sent.
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) LinkPreview"

    private sealed interface Entry {
        data class Loaded(
            val data: WebLinkPreviewData,
        ) : Entry
        data object Failed : Entry
    }

    private val cache = LruCache<String, Entry>(CACHE_SIZE)
    private val mutex = Mutex()

    suspend fun load(url: String): WebLinkPreviewData? {
        mutex.withLock {
            when (val cached = cache.get(url)) {
                is Entry.Loaded -> return cached.data
                Entry.Failed -> return null
                null -> Unit
            }
        }

        val result = withContext(Dispatchers.IO) {
            try {
                fetch(url)
            } catch (exception: IOException) {
                LogUtil.w(TAG, "Preview fetch failed: ${exception.javaClass.simpleName}")
                null
            } catch (exception: RuntimeException) {
                LogUtil.w(TAG, "Preview parse failed: ${exception.javaClass.simpleName}")
                null
            }
        }

        mutex.withLock {
            cache.put(url, result?.let { Entry.Loaded(it) } ?: Entry.Failed)
        }
        return result
    }

    private fun fetch(url: String): WebLinkPreviewData? {
        val pageUrl = toHttpsUrl(url) ?: return null
        val (finalUrl, html) = fetchHtml(pageUrl) ?: return null
        val meta = parseMeta(html)

        val title = meta["og:title"] ?: meta["twitter:title"] ?: parseTitleTag(html)
        val description = meta["og:description"]
            ?: meta["twitter:description"]
            ?: meta["description"]
        val imageUrl = (meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"])
            ?.let { resolveHttpsUrl(base = finalUrl, ref = it) }
        // A broken image should not hide the title and description.
        val image = imageUrl?.let { runCatching { fetchImage(it) }.getOrNull() }
        val host = (meta["og:site_name"] ?: finalUrl.host.removePrefix("www."))

        return WebLinkPreviewData(
            url = url,
            host = host,
            title = title?.let(::decodeEntities)?.take(300),
            description = description?.let(::decodeEntities)?.take(500),
            image = image,
        )
    }

    private fun toHttpsUrl(url: String): URL? {
        val trimmed = url.trim()
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // Upgrade plain http: cleartext is not allowed and https is almost always served.
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            else -> return null
        }
        return URL(withScheme)
    }

    private fun resolveHttpsUrl(base: URL, ref: String): URL? {
        val resolved = URL(base, ref.trim())
        return resolved.takeIf { it.protocol.equals("https", ignoreCase = true) }
    }

    private fun open(url: URL, accept: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                // Only follow redirects that stay on https.
                current = location?.let { resolveHttpsUrl(current, it) }
                    ?: throw IOException("Redirect to non-https location")
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects")
    }

    private fun fetchHtml(url: URL): Pair<URL, String>? {
        val connection = open(url, accept = "text/html,application/xhtml+xml")
        try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("html", ignoreCase = true)) return null

            val bytes = connection.inputStream.use { readLimited(it, MAX_HTML_BYTES) }
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(contentType)
                ?.groupValues
                ?.get(1)
                ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
                ?: Charsets.UTF_8
            return connection.url to String(bytes, charset)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchImage(url: URL): Bitmap? {
        val connection = open(url, accept = "image/*")
        try {
            if (connection.responseCode !in 200..299) return null
            if (!connection.contentType.orEmpty().startsWith("image/", ignoreCase = true)) {
                return null
            }
            val bytes = connection.inputStream.use { readLimited(it, MAX_IMAGE_BYTES) }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= TARGET_IMAGE_WIDTH) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private val metaTagRegex = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex(
        "([a-zA-Z:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')",
    )
    private val titleRegex = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun parseMeta(html: String): Map<String, String> {
        val head = html.substringBefore("</head>", missingDelimiterValue = html)
        val result = LinkedHashMap<String, String>()

        metaTagRegex.findAll(head).forEach { tag ->
            val attributes = attributeRegex.findAll(tag.value).associate { match ->
                match.groupValues[1].lowercase() to
                    match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            val key = (attributes["property"] ?: attributes["name"])?.lowercase()
            val content = attributes["content"]
            if (key != null && !content.isNullOrBlank() && key !in result) {
                result[key] = content.trim()
            }
        }
        return result
    }

    private fun parseTitleTag(html: String): String? {
        return titleRegex.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeEntities(text: String): String {
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

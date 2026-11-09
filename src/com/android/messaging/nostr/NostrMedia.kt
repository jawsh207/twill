package com.android.messaging.nostr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.android.messaging.nostr.core.Draft
import com.android.messaging.nostr.core.FileCipher
import com.android.messaging.nostr.core.Hex
import com.android.messaging.nostr.core.Kind
import com.android.messaging.nostr.core.Signer
import com.android.messaging.nostr.core.nowSeconds
import com.android.messaging.nostr.core.randomBytes
import com.android.messaging.nostr.core.sha256
import com.android.messaging.nostr.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A file ready to send: already encrypted, with what the recipient needs to decrypt it. */
internal data class PreparedUpload(
    val encrypted: ByteArray,
    val key: ByteArray,
    val nonce: ByteArray,
    val mimeType: String,
    val fileName: String?,
    val width: Int?,
    val height: Int?,
    val originalHash: String,
    /** Decrypted copy kept locally so our own message shows the file without downloading. */
    val localCopy: File,
)

/**
 * Attachments and profile pictures.
 *
 * - Sending: photos are re-encoded (dropping EXIF such as location), the file is encrypted
 *   with AES-GCM and uploaded to a Blossom server, which only ever sees ciphertext.
 * - Receiving: the encrypted blob is downloaded, its hash checked, then decrypted into
 *   app-private storage.
 * - Profile pictures: fetched over HTTPS, downscaled and cached in app-private storage.
 */
@Singleton
internal class NostrMedia @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mediaDir: File
        get() = File(context.filesDir, "nostr_media").apply { mkdirs() }

    private val avatarDir: File
        get() = File(context.cacheDir, "nostr_avatars").apply { mkdirs() }

    private val avatarMemory = LruCache<String, Bitmap>(AVATAR_MEMORY_ENTRIES)
    private val avatarLocks = HashMap<String, Mutex>()

    /** Reads, cleans and encrypts the file at [uri]. Throws [IOException] on failure. */
    suspend fun prepare(uri: Uri): PreparedUpload = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val declaredType = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(uri)

        val raw = resolver.openInputStream(uri)?.use { readLimited(it, MAX_UPLOAD_BYTES) }
            ?: throw IOException("Can't open file")

        val isReencodableImage = declaredType in REENCODABLE_IMAGE_TYPES
        val (bytes, mimeType, size) = if (isReencodableImage) {
            val cleaned = reencodeImage(raw)
            Triple(cleaned.first, "image/jpeg", cleaned.second)
        } else {
            val bounds = if (declaredType.startsWith("image/")) imageBounds(raw) else null
            Triple(raw, declaredType, bounds)
        }

        val key = randomBytes(FILE_KEY_BYTES)
        val nonce = randomBytes(FILE_NONCE_BYTES)
        val encrypted = FileCipher.encrypt(bytes, key, nonce)
        val localCopy = File(mediaDir, "out-" + System.nanoTime())
        localCopy.writeBytes(bytes)

        PreparedUpload(
            encrypted = encrypted,
            key = key,
            nonce = nonce,
            mimeType = mimeType,
            fileName = name?.let {
                if (isReencodableImage) it.substringBeforeLast('.') + ".jpg" else it
            },
            width = size?.first,
            height = size?.second,
            originalHash = Hex.encode(sha256(bytes)),
            localCopy = localCopy,
        )
    }

    /** Uploads encrypted bytes to the Blossom [server]. Returns the file's URL. */
    suspend fun upload(
        encrypted: ByteArray,
        server: String,
        signer: Signer,
    ): String {
        val hash = Hex.encode(sha256(encrypted))
        val now = nowSeconds()
        val auth = signer.sign(
            Draft(
                pubkey = signer.pubkey,
                createdAt = now,
                kind = Kind.BLOSSOM_AUTH,
                tags = listOf(
                    listOf("t", "upload"),
                    listOf("x", hash),
                    listOf("size", encrypted.size.toString()),
                    listOf("expiration", (now + BLOSSOM_AUTH_SECONDS).toString()),
                ) + listOfNotNull(serverHost(server)?.let { listOf("server", it) }),
                content = "Upload encrypted file",
            ),
        )
        val header = "Nostr " + Base64.encodeToString(auth.toJson().toByteArray(), Base64.NO_WRAP)

        return withContext(Dispatchers.IO) {
            val reply = try {
                Http.put(
                    url = server.trimEnd('/') + "/upload",
                    body = encrypted,
                    type = "application/octet-stream",
                    headers = mapOf("Authorization" to header, "X-SHA-256" to hash),
                )
            } catch (e: Http.StatusException) {
                throw IOException("Upload failed: ${e.message}", e)
            }
            val url = reply.optString("url")
            if (url.isNullOrBlank()) throw IOException("Upload failed: no URL returned")
            url
        }
    }

    private fun serverHost(server: String): String? =
        runCatching { Uri.parse(server).host?.lowercase() }.getOrNull()

    /** Downloads, verifies and decrypts [attachment]. Returns the local file. */
    suspend fun download(messageId: String, attachment: NostrAttachment): File =
        withContext(Dispatchers.IO) {
            if (!attachment.url.startsWith("https://", ignoreCase = true)) {
                throw IOException("Only https attachments are supported")
            }
            val encrypted = Http.bytes(attachment.url, MAX_DOWNLOAD_BYTES)

            attachment.encryptedHash?.let { expected ->
                if (!Hex.encode(sha256(encrypted)).equals(expected, ignoreCase = true)) {
                    throw IOException("File doesn't match its hash")
                }
            }

            val plain = runCatching {
                FileCipher.decrypt(
                    encrypted,
                    Hex.decode(attachment.keyHex),
                    Hex.decode(attachment.nonceHex),
                )
            }.getOrNull() ?: throw IOException("Can't decrypt file")
            val file = File(mediaDir, "in-" + messageId.take(32))
            file.writeBytes(plain)
            file
        }

    /** A downscaled bitmap of a local image, for showing in a bubble. */
    suspend fun loadPreview(path: String, maxSide: Int = PREVIEW_MAX_SIDE): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            decodeSampled(file.readBytes(), maxSide)
        }

    /** A profile picture, from memory, disk cache or the network (https only). */
    suspend fun loadAvatar(url: String): Bitmap? {
        avatarMemory.get(url)?.let { return it }
        if (!url.startsWith("https://", ignoreCase = true)) return null

        val lock = synchronized(avatarLocks) { avatarLocks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            avatarMemory.get(url)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val cacheFile = File(avatarDir, Hex.encode(sha256(url.toByteArray())))
                val bytes = if (cacheFile.exists()) {
                    cacheFile.readBytes()
                } else {
                    // Many hosts label pictures as octet-stream (or not at all), so only
                    // pages are ruled out; decoding decides the rest.
                    runCatching { Http.media(url, MAX_AVATAR_BYTES) }.getOrNull()
                        ?.also { downloaded ->
                            decodeSampled(downloaded, AVATAR_SIDE)?.let { small ->
                                cacheFile.outputStream().use { out ->
                                    small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                                }
                            }
                        }
                }
                bytes?.let { decodeSampled(it, AVATAR_SIDE) }?.also { avatarMemory.put(url, it) }
            }
        }
    }

    fun mimeExtension(mimeType: String?): String =
        mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Decodes, applies EXIF rotation, downsizes and re-encodes as JPEG without metadata. */
    private fun reencodeImage(raw: ByteArray): Pair<ByteArray, Pair<Int, Int>> {
        val decoded = decodeSampled(raw, MAX_SENT_IMAGE_SIDE) ?: throw IOException("Not an image")
        val rotation = runCatching {
            when (
                ExifInterface(raw.inputStream()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val oriented = if (rotation != 0) {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray() to (oriented.width to oriented.height)
    }

    private fun imageBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0) options.outWidth to options.outHeight else null
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val (width, height) = imageBounds(bytes) ?: return null
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("File is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 60 * 1024 * 1024
        private const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
        private const val MAX_SENT_IMAGE_SIDE = 2560
        private const val PREVIEW_MAX_SIDE = 1080
        private const val AVATAR_SIDE = 160
        private const val AVATAR_MEMORY_ENTRIES = 100
        private const val JPEG_QUALITY = 88
        private const val FILE_KEY_BYTES = 32
        private const val FILE_NONCE_BYTES = 16
        private const val BLOSSOM_AUTH_SECONDS = 300L

        private val REENCODABLE_IMAGE_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
        )
    }
}

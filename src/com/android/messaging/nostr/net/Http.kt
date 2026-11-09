package com.android.messaging.nostr.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Plain HTTPS through the platform stack (from Struthio). */
internal object Http {
    private const val MAX_JSON = 256 * 1024
    private const val MAX_FILE = 64 * 1024 * 1024

    /** Thrown for a server's error status, with the HTTP status. */
    class StatusException(
        val code: Int,
        message: String,
    ) : IOException(message)

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection {
        require(url.startsWith("https://", ignoreCase = true)) { "only https is allowed" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", "Twill")
        }
    }

    private fun read(conn: HttpURLConnection, limit: Int): ByteArray =
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IOException("File is too large")
            }
            out.toByteArray()
        }

    /**
     * Reads a whole response. The connection is only dropped on failure: fully read, it goes
     * back to the pool, and the next request to that server skips the connection and TLS setup.
     */
    private fun fetch(conn: HttpURLConnection, limit: Int): ByteArray = try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw StatusException(code, conn.getHeaderField("X-Reason") ?: "HTTP $code")
        }
        read(conn, limit)
    } catch (e: IOException) {
        conn.disconnect()
        throw e
    }

    /** NIP-05 forbids following redirects, so that is the caller's choice. */
    fun json(url: String, followRedirects: Boolean = true): JSONObject =
        JSONObject(String(fetch(open(url, followRedirects), MAX_JSON)))

    fun bytes(url: String, limit: Int = MAX_FILE): ByteArray = fetch(open(url, true), limit)

    /** Like [bytes], but null for a page (text/…) instead of a picture or file. */
    fun media(url: String, limit: Int): ByteArray? {
        val conn = open(url, true)
        return try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentType.orEmpty().startsWith("text/")) return null
            read(conn, limit)
        } catch (e: IOException) {
            conn.disconnect()
            null
        }
    }

    /** Blossom upload: the body is sent as is, and the server describes what it stored. */
    fun put(url: String, body: ByteArray, type: String, headers: Map<String, String>): JSONObject {
        val conn = open(url, true)
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.readTimeout = 120_000
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", type)
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        conn.outputStream.use { it.write(body) }
        return JSONObject(String(fetch(conn, MAX_JSON)))
    }
}

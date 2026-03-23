package com.catchkt.engine.sniffer

import android.webkit.CookieManager
import com.catchkt.data.model.SniffedResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnifferEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val _sniffedResources = MutableSharedFlow<SniffedResource>(extraBufferCapacity = 16)
    val sniffedResources: SharedFlow<SniffedResource> = _sniffedResources.asSharedFlow()

    // Concurrency-safe URL tracking: normalized URL -> probe Job
    private val probingUrls = ConcurrentHashMap<String, Job>()

    // All discovered resources (for the UI list)
    private val _discoveredResources = mutableListOf<SniffedResource>()
    val discoveredResources: List<SniffedResource> get() = _discoveredResources.toList()

    // Media MIME type prefixes to detect
    private val mediaTypePrefixes = listOf("video/", "audio/")

    // File extensions that hint at media content
    private val mediaExtensions = setOf(
        "mp4", "webm", "mkv", "avi", "mov", "flv", "wmv", "m4v",
        "mp3", "aac", "ogg", "wav", "flac", "m4a", "wma",
        "ts", "3gp"
    )

    // Extensions to skip (streaming manifests)
    private val unsupportedExtensions = setOf("m3u8", "mpd")

    fun onUrlIntercepted(url: String, scope: CoroutineScope) {
        val normalizedUrl = normalizeForDedup(url)

        // Skip if already probing or probed
        if (probingUrls.containsKey(normalizedUrl)) return

        // Quick heuristic: check URL extension
        val pathPart = url.split("?").first().split("#").first()
        val extension = pathPart.substringAfterLast('.', "").lowercase()
        val isUnsupported = extension in unsupportedExtensions

        if (isUnsupported) return

        val looksLikeMedia = extension in mediaExtensions

        // Check URL patterns common for media streams
        val urlLower = url.lowercase()
        val hasMediaHint = looksLikeMedia ||
            urlLower.contains("videoplayback") ||
            urlLower.contains("/video/") ||
            urlLower.contains("/audio/") ||
            urlLower.contains("mime=video") ||
            urlLower.contains("mime=audio") ||
            urlLower.contains("mime%3dvideo") ||
            urlLower.contains("mime%3daudio")

        if (!hasMediaHint) return

        val job = scope.launch {
            try {
                // Try URL-param extraction first (works for YouTube, etc.)
                val fromParams = tryExtractFromUrlParams(url)
                if (fromParams != null) {
                    addResource(fromParams)
                    return@launch
                }

                // Fall back to HEAD probe with WebView cookies
                probeUrl(url)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                // Probe failed silently
            }
        }
        probingUrls[normalizedUrl] = job
    }

    /**
     * Extract media info from URL query parameters.
     * YouTube videoplayback URLs contain mime=, clen=, etc.
     */
    private fun tryExtractFromUrlParams(url: String): SniffedResource? {
        val urlLower = url.lowercase()
        if (!urlLower.contains("videoplayback")) return null

        val params = parseQueryParams(url)
        val mimeRaw = params["mime"] ?: return null
        val mime = try {
            URLDecoder.decode(mimeRaw, "UTF-8").lowercase()
        } catch (_: Exception) {
            mimeRaw.lowercase()
        }

        val isMedia = mediaTypePrefixes.any { mime.startsWith(it) }
        if (!isMedia) return null

        val contentLength = params["clen"]?.toLongOrNull() ?: -1L

        // Skip tiny files
        if (contentLength in 1..50_000) return null

        val fileName = extractFileName(url, mime)

        return SniffedResource(
            url = url,
            contentType = mime,
            contentLength = contentLength,
            fileName = fileName
        )
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()

        return query.split('&').mapNotNull { param ->
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1]
            else null
        }.toMap()
    }

    private suspend fun probeUrl(url: String) {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .head()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

            // Pass cookies from WebView's CookieManager
            try {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    requestBuilder.header("Cookie", cookies)
                }
            } catch (_: Exception) {
                // CookieManager might not be initialized
            }

            val request = requestBuilder.build()
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext

                val contentType = resp.header("Content-Type")?.lowercase() ?: return@withContext
                val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L

                val isMedia = mediaTypePrefixes.any { contentType.startsWith(it) }
                if (!isMedia) return@withContext

                // Skip tiny files (tracking pixels, etc.)
                if (contentLength in 1..50_000) return@withContext

                val fileName = extractFileName(url, contentType)

                addResource(
                    SniffedResource(
                        url = url,
                        contentType = contentType,
                        contentLength = contentLength,
                        fileName = fileName
                    )
                )
            }
        }
    }

    private suspend fun addResource(resource: SniffedResource) {
        // Avoid duplicates by fileName + contentType
        val isDuplicate = _discoveredResources.any {
            it.contentType == resource.contentType &&
                it.contentLength == resource.contentLength &&
                it.contentLength > 0
        }
        if (isDuplicate) return

        _discoveredResources.add(resource)
        _sniffedResources.emit(resource)
    }

    private fun extractFileName(url: String, contentType: String): String {
        val urlPath = url.split("?").first().split("#").first()
        val lastSegment = urlPath.substringAfterLast('/')

        val baseName = if (lastSegment.isNotBlank() && lastSegment.contains('.') &&
            lastSegment.length < 100 && !lastSegment.contains("videoplayback")
        ) {
            lastSegment.take(100)
        } else {
            val ext = when {
                contentType.contains("mp4") -> "mp4"
                contentType.contains("webm") -> "webm"
                contentType.contains("mpeg") -> "mp3"
                contentType.contains("ogg") -> "ogg"
                contentType.contains("wav") -> "wav"
                contentType.contains("aac") -> "aac"
                contentType.contains("mp2t") -> "ts"
                else -> "bin"
            }
            "media_${System.currentTimeMillis()}.$ext"
        }

        // Sanitize filename
        return baseName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
    }

    /**
     * Normalize URL for deduplication.
     * For videoplayback URLs, use mime+clen as the key to avoid
     * treating the same stream with different tokens as different.
     */
    private fun normalizeForDedup(url: String): String {
        val urlLower = url.lowercase()
        if (urlLower.contains("videoplayback")) {
            val params = parseQueryParams(url)
            val mime = params["mime"] ?: ""
            val clen = params["clen"] ?: ""
            val itag = params["itag"] ?: ""
            if (mime.isNotEmpty() || itag.isNotEmpty()) {
                return "videoplayback:$mime:$clen:$itag"
            }
        }
        return url.split("?").first().split("#").first()
    }

    fun clearHistory() {
        probingUrls.keys.forEach { key ->
            probingUrls[key]?.cancel()
        }
        probingUrls.clear()
        _discoveredResources.clear()
    }
}

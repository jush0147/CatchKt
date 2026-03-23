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

    private val probingUrls = ConcurrentHashMap<String, Job>()

    private val _discoveredResources = mutableListOf<SniffedResource>()
    val discoveredResources: List<SniffedResource> get() = _discoveredResources.toList()

    private val mediaTypePrefixes = listOf("video/", "audio/")

    private val mediaExtensions = setOf(
        "mp4", "webm", "mkv", "avi", "mov", "flv", "wmv", "m4v",
        "mp3", "aac", "ogg", "wav", "flac", "m4a", "wma",
        "ts", "3gp"
    )

    private val unsupportedExtensions = setOf("m3u8", "mpd")

    // Minimum file size to consider as real media (500KB)
    private val MIN_MEDIA_SIZE = 500_000L

    fun onUrlIntercepted(url: String, scope: CoroutineScope) {
        val normalizedUrl = normalizeForDedup(url)

        if (probingUrls.containsKey(normalizedUrl)) return

        val pathPart = url.split("?").first().split("#").first()
        val extension = pathPart.substringAfterLast('.', "").lowercase()
        val isUnsupported = extension in unsupportedExtensions

        if (isUnsupported) return

        val looksLikeMedia = extension in mediaExtensions

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
                // Try URL-param extraction first (YouTube videoplayback)
                val fromParams = tryExtractFromUrlParams(url)
                if (fromParams != null) {
                    addResource(fromParams)
                    return@launch
                }

                // Fall back to HTTP probe with WebView cookies
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
     * Extract media info from YouTube videoplayback URL params.
     * These URLs contain mime=, clen=, itag= directly.
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

        // Skip small files (likely ads, previews, tracking)
        if (contentLength in 1 until MIN_MEDIA_SIZE) return null

        val itag = params["itag"] ?: ""
        val quality = itagToQuality(itag)
        val fileName = buildYouTubeFileName(mime, quality, itag)

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
            val cookies = try {
                CookieManager.getInstance().getCookie(url)
            } catch (_: Exception) {
                null
            }

            // Try HEAD first, then GET with Range: bytes=0-0 as fallback
            val contentType: String
            val contentLength: Long

            val headResult = tryHead(url, cookies)
            if (headResult != null) {
                contentType = headResult.first
                contentLength = headResult.second
            } else {
                // HEAD failed - try GET with Range to just get headers
                val getResult = tryGetRange(url, cookies) ?: return@withContext
                contentType = getResult.first
                contentLength = getResult.second
            }

            val isMedia = mediaTypePrefixes.any { contentType.startsWith(it) }
            if (!isMedia) return@withContext

            // Skip tiny files
            if (contentLength in 1 until MIN_MEDIA_SIZE) return@withContext

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

    private fun tryHead(url: String, cookies: String?): Pair<String, Long>? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", UA)
            if (!cookies.isNullOrBlank()) {
                builder.header("Cookie", cookies)
            }
            val response = okHttpClient.newCall(builder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val ct = resp.header("Content-Type")?.lowercase() ?: return null
                val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                ct to cl
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryGetRange(url: String, cookies: String?): Pair<String, Long>? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", UA)
                .header("Range", "bytes=0-0")
            if (!cookies.isNullOrBlank()) {
                builder.header("Cookie", cookies)
            }
            val response = okHttpClient.newCall(builder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return null
                val ct = resp.header("Content-Type")?.lowercase() ?: return null
                // For 206 responses, Content-Range header has the total size
                val contentRange = resp.header("Content-Range")
                val cl = if (contentRange != null) {
                    // Format: bytes 0-0/TOTAL_SIZE
                    contentRange.substringAfter('/', "").toLongOrNull() ?: -1L
                } else {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                ct to cl
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun addResource(resource: SniffedResource) {
        // Dedup: same contentType + same known size = duplicate
        val isDuplicate = _discoveredResources.any {
            it.contentType == resource.contentType &&
                it.contentLength == resource.contentLength &&
                it.contentLength > 0
        }
        if (isDuplicate) return

        _discoveredResources.add(resource)
        _sniffedResources.emit(resource)
    }

    /**
     * Map YouTube itag to human-readable quality string.
     */
    private fun itagToQuality(itag: String): String {
        return when (itag) {
            // Video + Audio (progressive)
            "18" -> "360p"
            "22" -> "720p"
            "37" -> "1080p"
            "38" -> "4K"
            // Video only (DASH)
            "133" -> "240p"
            "134" -> "360p"
            "135" -> "480p"
            "136" -> "720p"
            "137" -> "1080p"
            "138" -> "4K"
            "160" -> "144p"
            "264" -> "1440p"
            "266" -> "2160p"
            "298" -> "720p60"
            "299" -> "1080p60"
            "302" -> "720p60"
            "303" -> "1080p60"
            // VP9 video only
            "242" -> "240p"
            "243" -> "360p"
            "244" -> "480p"
            "247" -> "720p"
            "248" -> "1080p"
            "271" -> "1440p"
            "313" -> "2160p"
            "315" -> "2160p60"
            // Audio only
            "139" -> "48kbps"
            "140" -> "128kbps"
            "141" -> "256kbps"
            "171" -> "128kbps"
            "172" -> "256kbps"
            "249" -> "50kbps"
            "250" -> "70kbps"
            "251" -> "160kbps"
            else -> ""
        }
    }

    private fun buildYouTubeFileName(mime: String, quality: String, itag: String): String {
        val ext = when {
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("mp4a") || mime.contains("aac") || mime.contains("m4a") -> "m4a"
            mime.contains("mpeg") -> "mp3"
            mime.contains("ogg") || mime.contains("opus") -> "ogg"
            else -> "bin"
        }
        val typeLabel = when {
            mime.startsWith("video/") -> "影片"
            mime.startsWith("audio/") -> "音訊"
            else -> "媒體"
        }
        val qualityStr = if (quality.isNotEmpty()) " $quality" else ""
        return "YouTube_${typeLabel}${qualityStr}.$ext"
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

        return baseName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
    }

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

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

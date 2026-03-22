package com.catchkt.engine.sniffer

import com.catchkt.data.model.SniffedResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnifferEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val _sniffedResources = MutableSharedFlow<SniffedResource>(extraBufferCapacity = 16)
    val sniffedResources: SharedFlow<SniffedResource> = _sniffedResources.asSharedFlow()

    // Concurrency-safe URL tracking: URL -> probe Job
    private val probingUrls = ConcurrentHashMap<String, Job>()

    // Media MIME type prefixes to detect
    private val mediaTypePrefixes = listOf("video/", "audio/")

    // File extensions that hint at media content
    private val mediaExtensions = setOf(
        "mp4", "webm", "mkv", "avi", "mov", "flv", "wmv", "m4v",
        "mp3", "aac", "ogg", "wav", "flac", "m4a", "wma",
        "ts", "3gp"
    )

    // Extensions to skip (streaming formats out of MVP scope)
    private val unsupportedExtensions = setOf("m3u8", "mpd")

    fun onUrlIntercepted(url: String, scope: CoroutineScope) {
        val normalizedUrl = url.split("?").first().split("#").first()

        // Skip if already probing or probed
        if (probingUrls.containsKey(normalizedUrl)) return

        // Quick heuristic: check URL extension
        val extension = normalizedUrl.substringAfterLast('.', "").lowercase()
        val looksLikeMedia = extension in mediaExtensions
        val isUnsupported = extension in unsupportedExtensions

        if (isUnsupported) return

        // Also check URL patterns common for media streams
        val urlLower = url.lowercase()
        val hasMediaHint = looksLikeMedia ||
            urlLower.contains("videoplayback") ||
            urlLower.contains("/video/") ||
            urlLower.contains("/audio/") ||
            urlLower.contains("mime=video") ||
            urlLower.contains("mime=audio")

        if (!hasMediaHint) return

        val job = scope.launch {
            try {
                probeUrl(url)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                // Probe failed silently - don't crash the sniffer
            }
        }
        probingUrls[normalizedUrl] = job
    }

    private suspend fun probeUrl(url: String) {
        val request = Request.Builder()
            .url(url)
            .head()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return

            val contentType = resp.header("Content-Type")?.lowercase() ?: return
            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L

            val isMedia = mediaTypePrefixes.any { contentType.startsWith(it) }
            if (!isMedia) return

            // Skip tiny files (likely not real media, probably tracking pixels)
            if (contentLength in 1..50_000) return

            val fileName = extractFileName(url, contentType)

            _sniffedResources.emit(
                SniffedResource(
                    url = url,
                    contentType = contentType,
                    contentLength = contentLength,
                    fileName = fileName
                )
            )
        }
    }

    private fun extractFileName(url: String, contentType: String): String {
        val urlPath = url.split("?").first().split("#").first()
        val lastSegment = urlPath.substringAfterLast('/')

        val baseName = if (lastSegment.isNotBlank() && lastSegment.contains('.')) {
            lastSegment.take(100) // Limit filename length
        } else {
            val ext = when {
                contentType.contains("mp4") -> "mp4"
                contentType.contains("webm") -> "webm"
                contentType.contains("mpeg") -> "mp3"
                contentType.contains("ogg") -> "ogg"
                contentType.contains("wav") -> "wav"
                contentType.contains("aac") -> "aac"
                else -> "bin"
            }
            "media_${System.currentTimeMillis()}.$ext"
        }

        // Sanitize filename: remove illegal characters
        return baseName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
    }

    fun clearHistory() {
        probingUrls.keys.forEach { key ->
            probingUrls[key]?.cancel()
        }
        probingUrls.clear()
    }
}

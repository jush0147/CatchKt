package com.catchkt.sniffer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BrowserViewModel : ViewModel() {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val probingJobs = ConcurrentHashMap<String, Job>()
    private val discoveredResources = ConcurrentHashMap<String, SniffedResource>()

    private val _sniffedResourceEvents = MutableSharedFlow<SniffedResource>(extraBufferCapacity = 16)
    val sniffedResourceEvents: SharedFlow<SniffedResource> = _sniffedResourceEvents

    fun submitInterceptedUrl(url: String) {
        if (!isCandidateMediaUrl(url)) return
        if (discoveredResources.containsKey(url)) return

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val mimeType = response.header("Content-Type")?.substringBefore(';') ?: return@use
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) return@use

                    val resource = SniffedResource(
                        url = url,
                        mimeType = mimeType,
                        contentLength = contentLength
                    )
                    discoveredResources[url] = resource
                    _sniffedResourceEvents.tryEmit(resource)
                }
            } finally {
                probingJobs.remove(url)
            }
        }

        val existingJob = probingJobs.putIfAbsent(url, job)
        if (existingJob != null) {
            job.cancel()
        }
    }

    private fun isCandidateMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".m3u8")) return false

        val mediaPatterns = listOf(
            ".mp4", ".webm", ".mkv", ".mov", ".m4v", ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac"
        )
        return mediaPatterns.any { lower.contains(it) } ||
            lower.contains("video") ||
            lower.contains("audio")
    }
}

data class SniffedResource(
    val url: String,
    val mimeType: String,
    val contentLength: Long
)

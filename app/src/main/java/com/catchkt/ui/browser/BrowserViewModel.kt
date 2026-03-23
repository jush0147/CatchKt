package com.catchkt.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catchkt.data.model.DownloadTask
import com.catchkt.data.model.SniffedResource
import com.catchkt.data.repository.DownloadRepository
import com.catchkt.engine.download.DownloadEngine
import com.catchkt.engine.download.DownloadProgress
import com.catchkt.engine.sniffer.SnifferEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val snifferEngine: SnifferEngine,
    private val downloadEngine: DownloadEngine,
    private val repository: DownloadRepository
) : ViewModel() {

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0)
    val loadingProgress: StateFlow<Int> = _loadingProgress.asStateFlow()

    private val _sniffedResources = MutableStateFlow<List<SniffedResource>>(emptyList())
    val sniffedResources: StateFlow<List<SniffedResource>> = _sniffedResources.asStateFlow()

    private val _showDownloadSheet = MutableStateFlow(false)
    val showDownloadSheet: StateFlow<Boolean> = _showDownloadSheet.asStateFlow()

    val downloadTasks: StateFlow<List<DownloadTask>> = repository.getAllTasks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadProgress: StateFlow<Map<Long, DownloadProgress>> = downloadEngine.progressUpdates

    init {
        viewModelScope.launch {
            snifferEngine.sniffedResources.collect { resource ->
                _sniffedResources.value = snifferEngine.discoveredResources
                // Don't auto-show sheet - just update badge count
                // User can tap the download button to see detected resources
            }
        }
    }

    fun onUrlChanged(url: String) {
        _currentUrl.value = url
    }

    fun onPageStarted(url: String) {
        _currentUrl.value = url
        _isLoading.value = true
        _loadingProgress.value = 0
    }

    fun clearSniffedResources() {
        snifferEngine.clearHistory()
        _sniffedResources.value = emptyList()
    }

    fun onPageFinished() {
        _isLoading.value = false
        _loadingProgress.value = 100
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
    }

    fun onUrlIntercepted(url: String) {
        snifferEngine.onUrlIntercepted(url, viewModelScope)
    }

    fun dismissDownloadSheet() {
        _showDownloadSheet.value = false
    }

    fun toggleDownloadList() {
        _showDownloadSheet.value = !_showDownloadSheet.value
    }
}

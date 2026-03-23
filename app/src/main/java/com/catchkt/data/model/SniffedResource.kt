package com.catchkt.data.model

data class SniffedResource(
    val url: String,
    val contentType: String,
    val contentLength: Long,
    val fileName: String
)

package com.khalsatorrent.cs3builder.model

data class NetworkLog(
    val id: String,
    val timestamp: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val responseCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val cookies: List<String>,
    val mimeType: String?,
    val isXHR: Boolean,
    val duration: Long
)

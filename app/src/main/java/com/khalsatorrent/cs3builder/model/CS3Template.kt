package com.khalsatorrent.cs3builder.model

data class CS3Template(
    val baseUrl: String,
    val providerName: String,
    val detectedHeaders: Map<String, String>,
    val detectedCookies: List<String>,
    val searchEndpoint: String?,
    val searchQueryParam: String?,
    val mediaUrls: List<String>,
    val apiEndpoints: List<String>,
    val generatedCode: String,
    val logSummary: String
)

data class DetectedPattern(
    val type: PatternType,
    val url: String,
    val description: String,
    val method: String = "GET",
    val responseCode: Int = 0,
    val responseBody: String? = null
)

enum class PatternType {
    SEARCH_ENDPOINT,
    EPISODE_API,
    MEDIA_URL,
    API_ENDPOINT,
    STREAMING_URL
}

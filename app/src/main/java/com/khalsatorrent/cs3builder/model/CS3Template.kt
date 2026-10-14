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
    val description: String
)

enum class PatternType {
    SEARCH_ENDPOINT,
    MEDIA_URL,
    API_ENDPOINT,
    DETAIL_PAGE,
    STREAMING_URL
}

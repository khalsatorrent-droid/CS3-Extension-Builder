package com.khalsatorrent.cs3builder.model

/** One file of the generated CloudStream plugin module. [path] is relative to the module root. */
data class GeneratedFile(
    val name: String,
    val path: String,
    val content: String
)

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
    val logSummary: String,
    val files: List<GeneratedFile> = emptyList()
)

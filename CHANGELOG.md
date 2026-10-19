# Changelog

All notable changes to CS3 Extension Builder will be documented in this file.

## [Unreleased]

## [1.0.1] - 2026-10-19

### Changed
- See commit log for details

## [1.0.0] - 2026-10-14

### Added
- WebView browser with full HTTP traffic interception (OkHttp + JS injection)
- Captures request/response headers, cookies, bodies (up to 50KB) for every network call
- JavaScript fetch and XHR monkey-patching for complete traffic coverage
- Real-time network log viewer with color-coded method and status badges
- Automatic CloudStream 3 (CS3) extension code generation from captured traffic
- Pattern detection for search endpoints, streaming URLs (.m3u8, .mp4, etc.), and JSON APIs
- Generated Kotlin code includes defaultHeaders, search(), getMainPage(), load(), loadLinks()
- Share generated `.kt` file via Android share sheet
- Share code + log summary directly with Claude (pre-formatted prompt)
- GitHub Actions CI: debug APK built on every push

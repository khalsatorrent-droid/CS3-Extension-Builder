# Changelog

All notable changes to CS3 Extension Builder will be documented in this file.

## [Unreleased]

### Added
- **Deep capture**: the browser now fetches page/API requests itself so every log has the real status code, response headers, Set-Cookie and response body (falls back to the WebView automatically for redirects, video and Cloudflare challenges)
- Capture hook injected at document start into **every frame**, so embedded players/iframes are captured; logs fetch/XHR request + response bodies, response headers, WebSockets and `<video src>`
- Rendered **page HTML snapshots** (DOM) for JS-built sites, plus cookies on every request
- **Apps tab**: capture another app's traffic with Reqable / HttpCanary / PCAPdroid (one-tap PCAPdroid capture filtered to the app) and **import HAR** files into Logs
- Log viewer: search, filters (API / Streams / Pages / Main site / Errors), hide noise, size & timing, live count badge, detail sheet with copy URL / cURL / response
- Exports: Claude-ready log, HAR, session JSON; "→ Claude" sends the log + generated module + prompt
- Browser: ⋮ menu (deep capture, page snapshots, ad/popup blocking, desktop mode, pause, recent sites, cookies viewer, clear site data), fullscreen video, progress bar, recent-sites start page
- Session is saved and restored if the app is killed

### Changed
- **Code generator rewritten**: detects card/title/poster selectors from captured HTML, search (GET, form POST, JSON POST, path style), JSON response shapes → typed data classes, detail & episode selectors, home sections, pagination, DooPlay sites, server buttons, known embed hosts, and the Referer/Origin each stream needs
- Generates a complete plugin module (Provider, Plugin, build.gradle.kts, manifest, models, analysis notes) using `newExtractorLink`; shareable as a .zip
- Minimum Android version is now 8.0 (API 26)

### Fixed
- Generated JSON search code referenced an undefined variable
- Fragments duplicated / blank after the app was recreated; Logs toolbar menu showing on every tab

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

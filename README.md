# CS3 Extension Builder

An Android tool for building [CloudStream 3](https://github.com/recloudstream/cloudstream) extensions from real traffic. Browse a site (or record an app), and it captures every request with full details, then generates a ready-to-edit CloudStream plugin module from what it saw.

---

## What it does

**Browser tab — capture a website**
- **Deep capture** (on by default): page and API requests are fetched by the app on the WebView's behalf, so each log has the real status code, request/response headers, cookies and response body. Redirects, video files and bot challenges (Cloudflare, DDoS-Guard) are handed back to the WebView automatically.
- A capture script is injected at document start into **every frame**, including embedded players, recording fetch/XHR request + response bodies, response headers, WebSockets and `<video>` sources.
- **Page snapshots** store the rendered HTML of each page (for JS-built sites).
- ⋮ menu: deep capture, page snapshots, ad & popup blocking, desktop mode, pause capture, recent sites, view cookies, clear cookies & site data. Fullscreen video works.

**Apps tab — capture an app**
Android can't run another APK inside this app, so the Apps tab works with a capture tool:
1. Pick an installed app — you get step-by-step instructions.
2. Record it with **Reqable** or **HttpCanary** (or PCAPdroid, which the app can start pre-filtered to that app with TLS decryption).
3. Export a **HAR** file and tap **Import HAR** — the requests land in Logs and work with Generate like browser traffic.

Apps that pin certificates won't reveal their HTTPS contents; most streaming apps share an API with their website, so try the site in the Browser tab too.

**Logs tab**
Search across URLs, headers and bodies; filter by API / Streams / Pages / Main site / Errors; hide images/JS/CSS. Tap a request for everything about it (pretty-printed JSON, cURL, copy response). Export as a Claude-ready text log, HAR, or session JSON — or import HAR/session files.

**Code tab — generated extension**
The generator analyses the capture and builds a full plugin module:
- `XxxProvider.kt` — `mainPage`, `getMainPage`, `search`, `load`, `loadLinks` using `newExtractorLink`
- `XxxPlugin.kt`, `build.gradle.kts`, `AndroidManifest.xml`, JSON models, and `CAPTURE_NOTES.md` (what was detected and what to capture next)

It detects card / title / poster selectors from the captured HTML, search requests (GET, form POST, JSON POST, `/search/<q>`), JSON response shapes (typed data classes with the real field names), detail & episode selectors, home sections and pagination, DooPlay (WordPress) players, server buttons, known embed hosts (FileMoon, StreamWish, VidHide, MegaCloud…) and the Referer/Origin each stream needs.

Share it as a **.zip** module, copy individual files, or **→ Claude** to send the log, module and a review prompt together.

---

## Best capture flow

1. Open the site's home page
2. Search for a title
3. Open a movie or show page (and an episode for series)
4. Press play and let the video start
5. Logs → **Generate**

`CAPTURE_NOTES.md` tells you which of these steps are missing.

---

## Building

GitHub Actions builds a signed APK on every push to `master` and publishes it under Releases. Pull requests are compile-checked.

Locally: JDK 17, Android SDK 34, then

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 8.0+.

---

## Project structure

```
app/src/main/java/com/khalsatorrent/cs3builder/
├── MainActivity.kt              — bottom nav host (Browser, Apps, Logs, Code)
├── model/                       — NetworkLog + LogKind, generated template/files
├── network/
│   ├── LogRepository.kt         — capture store (merge, cap, pause, persist)
│   ├── LoggingWebViewClient.kt  — request interception, DOM snapshots, JS bridge
│   ├── DeepCapture.kt           — OkHttp fetch on the WebView's behalf
│   ├── CaptureScripts.kt        — injected fetch/XHR/WebSocket/media hook, ad blocker
│   └── AdBlocker.kt
├── codegen/
│   ├── SiteAnalyzer.kt          — base URL, search, pages, streams, embeds, APIs
│   ├── HtmlAnalyzer.kt          — jsoup selector detection (cards, detail, episodes)
│   ├── JsonShape.kt             — JSON list/field detection
│   └── CS3CodeGenerator.kt      — plugin module output
├── export/                      — Claude log, HAR, cURL export; HAR/session import
├── ui/                          — Browser, Apps, Logs (+ detail sheet), Code
└── utils/                       — share/zip helpers, prefs, code highlighter
```

---

## Notes

The generated module is a strong starting point, not a guarantee: sites with obfuscated players, signed tokens or encrypted APIs still need manual work (the Claude export is built for exactly that).

Use responsibly and respect the Terms of Service of sites and apps you capture.

## License

MIT

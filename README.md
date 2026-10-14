# CS3 Extension Builder

An Android developer tool that acts as a proxy-browser to capture and log all HTTP/HTTPS traffic, then auto-generates a [CloudStream 3](https://github.com/recloudstream/cloudstream) (CS3) Kotlin extension file from the observed network patterns.

---

## What it does

1. **Proxy Browser** — A built-in WebView browser that intercepts all outgoing requests via:
   - An OkHttp `Interceptor` that captures request/response headers, bodies, cookies, and timing
   - A `WebViewClient` override that logs every resource URL the page loads
   - JavaScript injection (`fetch` and `XMLHttpRequest` monkey-patching) to capture XHR/fetch calls that bypass the native interceptor

2. **Network Log Viewer** — A live-updating RecyclerView showing every captured request with method, URL, status code, and timestamp. Tap any entry for full detail: all headers, request/response bodies, and cookies.

3. **CS3 Code Generator** — Analyzes the captured logs and produces a complete, compilable Kotlin CloudStream 3 provider skeleton:
   - Detects the site's base URL from traffic patterns
   - Identifies search endpoints, media/streaming URLs (`.m3u8`, `.mp4`), API endpoints (JSON responses), and detail pages
   - Extracts common request headers and cookies
   - Generates `getMainPage()`, `search()`, `load()`, and `loadLinks()` stubs pre-filled with detected URLs and headers

4. **Share / Export** — The generated code can be:
   - Copied to clipboard
   - Saved and shared as a `.kt` file
   - Sent directly to Claude with a structured review prompt

---

## How to use it

### Step 1 — Browse
1. Open the app. You start on the **Browser** tab.
2. Type a URL (e.g. `https://streaming-site.example.com`) and tap **Go**.
3. Navigate to the site's search page, a show/movie detail page, and click **Play** to trigger the media player.
4. All requests are silently logged in the background.

### Step 2 — Review logs
1. Switch to the **Logs** tab.
2. You'll see every request the browser made. Look for:
   - `POST` or `GET` requests to `/api/search`
   - `.m3u8` or `.mp4` streaming URLs
   - JSON API calls that return show metadata
3. Tap any entry to see full request/response detail, including cookies.

### Step 3 — Generate CS3 code
1. From the **Logs** tab, tap the **Generate CS3** button in the toolbar (pencil icon).
2. The app analyzes the captured traffic and switches to the **Code** tab.

### Step 4 — Review and share
On the **Code** tab:
- **Copy** — copies the Kotlin code to clipboard
- **Share .kt** — shares the file via your OS share sheet (save to Drive, email, etc.)
- **Share with Claude** — creates a formatted message with both the log summary and generated code, ready to paste into Claude for review and improvement

---

## Building the app

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Steps
```bash
git clone https://github.com/khalsatorrent/CS3ExtensionBuilder.git
cd CS3ExtensionBuilder
./gradlew assembleDebug
```

Install the debug APK:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run it directly on a device or emulator.

---

## Important notes

### The generated code is a starting point
The auto-generated CS3 provider is a scaffold based on observed traffic patterns. It **will require manual refinement** before it works as a real CloudStream extension:

- CSS selectors for search results, titles, and posters are guessed and need adjustment
- The `loadLinks()` function identifies likely media URL patterns but the extraction logic may need tuning
- Authentication tokens and dynamic parameters cannot be fully inferred from static captures
- Sites that use encrypted or obfuscated JavaScript to build API calls will require manual reverse-engineering

### Use responsibly
This tool is intended for personal use to develop extensions for content you have the right to access. Respect the Terms of Service of any website you interact with.

### HTTPS inspection limitation
The app captures WebView network requests via `shouldInterceptRequest`, which sees URLs and headers but **not response bodies** for HTTPS resources in the WebView layer. Full response body capture only works for requests made via the OkHttp client (not currently wired into the WebView network stack). To capture full response bodies, use the app in combination with a separate proxy tool like mitmproxy.

---

## Project structure

```
app/src/main/java/com/khalsatorrent/cs3builder/
├── MainActivity.kt              — BottomNav host, 3 fragments
├── model/
│   ├── NetworkLog.kt            — Data class for captured requests
│   └── CS3Template.kt           — Data class for generated output
├── network/
│   ├── ProxyInterceptor.kt      — OkHttp interceptor + LogRepository singleton
│   └── LoggingWebViewClient.kt  — WebViewClient with JS injection
├── ui/
│   ├── BrowserFragment.kt       — WebView browser tab
│   ├── LogFragment.kt           — Log list tab
│   ├── CodeFragment.kt          — Generated code tab
│   └── LogAdapter.kt            — RecyclerView adapter
├── codegen/
│   └── CS3CodeGenerator.kt      — Pattern detection + Kotlin code generation
└── utils/
    └── ShareUtils.kt            — Clipboard, file share, Claude prompt builder
```

---

## License

MIT — see LICENSE file.

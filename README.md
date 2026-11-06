# andagent

[简体中文](README.zh-CN.md) · English

[![CI](https://github.com/congduan/andagent/actions/workflows/ci.yml/badge.svg)](https://github.com/congduan/andagent/actions/workflows/ci.yml) [![Release](https://github.com/congduan/andagent/actions/workflows/release.yml/badge.svg)](https://github.com/congduan/andagent/actions/workflows/release.yml) [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

An on-device AI assistant for Android. It calls device capabilities directly from the conversation — files, media store, calendar, Bluetooth/Wi-Fi, sensors, NFC, screen, shell, databases — and renders the results inline as rich cards: charts, images, maps, audio/video, tables, and widget previews.

- **Frontend**: `web/` — React 19 + `@assistant-ui/react` + `@mariozechner/pi-ai`, bundled with Vite and shipped inside the APK for offline use.
- **Native layer**: `app/` — a Kotlin full-screen WebView shell, the tool registry, and permission / system-API bridging.
- **Model**: any OpenAI-compatible endpoint. Requests are proxied through native OkHttp (to bypass WebView CORS) and streamed back over SSE; a single turn allows up to 200 tool rounds.
- **Version**: `0.28.7` (versionCode 44), `applicationId = com.dc.andagent`. `app/build.gradle.kts` is authoritative.

## How it works

```
User ⇄ WebView (React + assistant-ui + pi-ai)
       │  AndroidBridge  (JS ⇄ Kotlin)
       ├─ llmFetchStart/llmCancel → OkHttp → OpenAI-compatible endpoint (SSE stream back)
       └─ toolExec(name, argsJson) → Kotlin tool registry → system APIs / Shizuku / Termux / QuickJS
```

The WebView loads `https://appassets.androidplatform.net/assets/web/index.html` through `WebViewAssetLoader`: `/assets/**` maps to APK assets and `/local/**` is a read-only mapping to the app's external files directory (media playback, with Range/206 support and path-traversal protection). All JS/CSS/wasm is local — no CDN — but LLM requests and map cards still require network access.

## Capabilities

`MainActivity.kt` registers the native tool catalog. Each implements the `Tool` interface in `tools/Tool.kt` (JSON Schema parameters plus a `destructive` marker):

| Domain | Count | Representative tools |
|---|---|---|
| Device / system info | 31 | `get_time`, `device_info`, `battery_detail`, `clipboard_read/write`, `notify_send`, `app_list`, `open_uri`, `telephony_info`, `audio_state`, `torch`, `hotspot` |
| Files / media store / image editing | 28 | `file_list/read/write/search/download`, `file_copy/move/delete`, `album_photos`, `image_edit/preview/collage`, `saf_tree_*`, `storage_scan` |
| Calendar / alarms / timers / cron | 14 | `calendar_list/events/add/delete`, `alarm_set`, `timer_set`, `countdown`, `cron_create/list/toggle/delete/run_now` |
| Calls / location / recording / contacts / SMS | 8 | `phone_call`, `sms_send/read`, `location_get`, `mic_record`, `contacts_search`, `provider_query` |
| Bluetooth / Wi-Fi | 11 | `bt_scan/connect/toggle`, `wifi_connect/saved_list/forget/toggle`, `wifi_info` |
| Sensors / health | 4 | `sensor_list/read`, `steps_today/history` |
| Screen / input (Shizuku) | 9 | `screen_capture`, `screen_record*`, `screenshot_quick`, `input_tap/swipe/text` |
| Termux / shell | 4 | `termux_setup`, `shell_exec`, `pkg_install`, `apt_status` |
| QuickJS scripting | 8 | `js_eval`, `script_save/run/list/...`, `json_fix` |
| SQLite | 8 | `db_create/exec/query/...`, `db_gui` |
| Document generation | 3 | `docx_create`, `pptx_create`, `xlsx_create` |
| Home-screen widgets | 2 | `widget_generate`, `widget_save_and_pin` |
| NFC | 11 | `nfc_status/read/write_text/write_uri/write_raw/hce_status` (NDEF+HCE), `nfc_mf_dump`, `nfc_mf_write_block`, `nfc_ul_read`, `nfc_ul_write_page`, `nfc_keys_manage` (MIFARE Classic/Ultralight 深度读写) |
| Embedded web page in chat | 1 | `web_embed` |
| ZIP archives | 5 | `zip_create/list/extract/add/test` |
| Email (SMTP) | 3 | `email_send`, `email_verify`, `email_draft` |
| F-Droid / APK install | 3 | `fdroid_search`, `fdroid_download`, `app_install` |
| Web search / read | 2 | `web_search`, `web_read` |
| Intents | 3 | `intent_send`, `intent_resolve`, `intent_broadcast` |
| WebView DevTools | 5 | `devtools_status/debugging/eval/console/screenshot` |
| Layered memory / LiveLoop | — | Core and archived memory, continuous background work |
| Camera overlay / scheduled work | — | Floating preview, chat camera card, cron management |

Other features:

- **Rich rendering**: Markdown fenced blocks dispatch `chart` (ECharts), `photos`, `image`, `kv`, `geo`, `audio`, `video`, `camera`, and `widget` cards, plus highlight.js syntax highlighting and GFM tables.
- **Script engine**: QuickJS runs on a dedicated thread (64 MB memory limit, native interrupt-based timeout) and exposes the `andagent.tool/docx/pptx/xlsx/db/log` JS API. Scripts live in the app's external `scripts/` directory with path-traversal checks.
- **Home-screen widgets**: declarative `WidgetSpec v2` with STATIC / HTTP / SYSTEM / SCRIPT data sources and WorkManager-scheduled refreshes.
- **Database browser**: `dbgui/` provides a read-only SQL UI built on sql.js + Grid.js.
- **Document generation**: hand-written OOXML in pure Kotlin for docx/pptx/xlsx.
- **Layered memory**: core memories stay in context; relevant archived memories are retrieved as needed.
- **LiveLoop**: agent work can continue across scheduled wakeups and resume with stored context.
- **Scheduled tasks**: cron tasks can be created, paused, enabled, and inspected in Settings.
- **Camera overlay**: camera preview can float over the conversation for multimodal tasks.
- **Multi-session drawer, reasoning display, voice input, conversation export, dynamic permissions**: implemented in the frontend and bridge layers.

## Quick start

### Prerequisites

| Item | Requirement |
|---|---|
| JDK | 17 |
| Android SDK / compileSdk | SDK 34 |
| NDK / CMake | `26.1.10909125` / `3.22.1` (native code in `app/src/main/cpp`) |
| Gradle | 8.9 (use the bundled `./gradlew`; no installation needed) |
| Node.js | 20+; only needed to rebuild the frontend |
| Device | Android 8.0+ (minSdk 26); embedded Termux is `arm64-v8a` only |

Create `local.properties` at the repository root pointing to your Android SDK (already ignored by `.gitignore`):

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Build and install

```bash
./gradlew assembleDebug          # output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # JVM unit tests (JUnit4 + Robolectric)
```

`preBuild` automatically runs `buildWeb` (`npm ci` + `npm run build`), writing output to `app/src/main/assets/web` and `app/src/main/assets/dbgui`. If npm is missing or installation/build fails, the task prints a message and exits with `exit 0`, falling back to the **pre-built assets committed to the repository** — so an Android-only environment can still produce an APK.

To work on the frontend separately:

```bash
cd web
npm ci
npm run dev       # Vite dev server, UI preview only
npm run build     # esbuild bundles pi-ai + tsc --noEmit + Vite + dbgui + copy sql-wasm.wasm
```

Browser preview mode has no native bridge: tool execution and the LLM proxy are unavailable, and settings fall back to `localStorage`. Debuggable APKs can be inspected with `chrome://inspect`.

### First-run configuration

On first launch, if no model is configured, **Connect a model** opens automatically.
Choose a provider preset or a custom OpenAI-compatible endpoint, enter your key,
and fetch the model list or type a model ID. **Test and save** sends a short
request to verify tool calling (a small provider charge may apply). If listing
fails, enter the model ID manually and test again, or **Save without testing**.
Ollama and LM Studio allow an empty key; replace `localhost` with your computer's
LAN IP when connecting from the phone.

Choose **Later** to skip the first-run guide. The chat header's model button,
the unconfigured banner, and **Settings › Model configuration** always reopen it.
Existing valid configurations bypass onboarding. Provider presets include public
endpoint addresses; selected model settings and API keys are saved only on the device.
This setup flow follows [Mika's model connection flow](https://github.com/wangzan101/Mika/blob/main/docs/models.en.md)
and the local iOS-Agent implementation.

Alternatively, put your settings in `config.json` in the phone's `Download` directory:

```json
{
  "baseUrl": "https://your-openai-compatible-endpoint/v1",
  "apiKey": "your-api-key",
  "model": "your-model-id"
}
```

```bash
adb push config.json /sdcard/Download/config.json
# Android 11+ can deny direct reads of the public Downloads dir. Keep an
# identical copy in the app's private external files dir (no permission needed):
adb push config.json /sdcard/Android/data/com.dc.andagent/files/config.json
```

`SettingsStore` looks for `config.json` on startup and whenever the WebView
reads settings: public `Download/` (File API, then MediaStore), then the app's
private external files dir. `systemPrompt` is optional and overrides the
built-in prompt. Values from the file take precedence over
`SharedPreferences`; reopening Settings re-imports them. On Android 13+ the
public `Download/` copy is usually unreadable without "All files access", so
after editing it, re-run the second `adb push` to sync the private copy (or
grant all-files access, which makes the public file authoritative). Keep
`config.json` out of version control — it holds a plaintext credential. If no
config is present, use the model setup guide instead;
values are stored only in local `SharedPreferences`, never in web assets.
If you switch to the in-app setup, update or remove an existing config file to
prevent it from overriding your saved settings on the next settings read.

| Field | Notes |
|---|---|
| Base URL | OpenAI-compatible endpoint; **do not** append `/chat/completions` |
| API Key | Read from `Download/config.json`, or entered in Settings |
| Model ID | Model identifier from your provider |
| System Prompt | Defines the assistant's identity and behavior; optional in the config file |

`android:usesCleartextTraffic="true"` also allows self-hosted `http://` endpoints.

## Optional capabilities and permissions

The manifest declares many permissions, but **most are requested on demand via `PermissionBroker` when a tool is actually invoked**; only media-read and microphone (`RECORD_AUDIO`) permissions are requested at startup. The voice-input button uses `getUserMedia()` inside the WebView, so `WebChromeClient.onPermissionRequest` bridges the WebView's audio-capture request to the same runtime grant. The `destructive` marker denotes write operations the model should confirm with the user first, and the frontend highlights those tool cards with a "write" badge.

| Capability | Prerequisite |
|---|---|
| Regular tools | The corresponding runtime permission (calendar, location, microphone, notifications, media, phone/SMS, etc.) |
| Screenshots / recording, input injection, advanced Wi-Fi/Bluetooth control | Install and authorize [Shizuku](https://github.com/RikkaApps/Shizuku) (`moe.shizuku.privileged.api`); some operations need `WRITE_SECURE_SETTINGS` |
| Step history | Health Connect and its permission grant |
| NFC read / write | NFC-capable device; foreground dispatch, confirmation before writing |
| External Termux `RUN_COMMAND` | Termux installed and authorized; may also be granted via Shizuku `pm grant` |
| Embedded Termux userland | `arm64-v8a` device; see below |

Caveats: `targetSdk = 28` retains the legacy storage/permission model and is not suitable for Play Store distribution. The Release workflow produces an installable debug-signed APK; it has no production signing key or minification. CI runs web and Android builds and tests.

## Project layout

```
app/
  build.gradle.kts                 # Android config, buildWeb / downloadBootstrap tasks
  src/main/java/com/dc/andagent/
    MainActivity.kt                # WebView shell and native tool registry
    bridge/                        # AgentBridge / DbGuiBridge / PermissionBroker
    tools/                         # Tool implementations (30+ files)
    script/                        # QuickJS engine, JS API, js_eval
    shell/                         # Termux env/installer, ShizukuShell, memfd exec
    widget/                        # Widget spec, renderer, data sources, refresh scheduling
    docs/                          # Hand-written OOXML generators
    ui/SettingsStore.kt            # SharedPreferences settings
  src/main/cpp/                    # memexec / scriptinterrupt native code (CMake)
  src/main/assets/
    web/                           # Committed pre-built frontend output
    dbgui/                         # Read-only SQLite browser (incl. sql-wasm.wasm)
    bootstrap/                     # Embedded Termux bootstrap-aarch64.zip
    vendor/                        # scrcpy-server + license
  libs/README.md                   # QuickJS vendor jar / .so provenance and hashes
web/
  src/                             # App / agent (tool schemas) / bridge / cards
  dbgui/                           # Separate entry point for the database browser
  scripts/build-pi.mjs             # esbuild pre-bundles pi-ai → pi-bundle.js
scripts/rewrite_termux_bootstrap.py
ATTRIBUTION.md                     # Termux code adaptation and license notes
ROADMAP-TODO.md                    # Progress, backlog, and milestones
```

### Adding a tool

1. Implement `Tool` in `app/src/main/java/com/dc/andagent/tools/` (`name` / `description` / `parameters` / `destructive` / `execute`).
2. Register it in the `tools.putAll(mapOf(...))` block in `MainActivity.kt`.
3. Add the matching JSON Schema in `web/src/agent.ts` — **both sides must stay in sync**, otherwise the model cannot see or call the tool.
4. If a new permission is required, update `AndroidManifest.xml` and request it through `PermissionBroker.ensure(...)`.

Bridge protocol: JS → Kotlin via `window.AndroidBridge.*` (`toolExec`, `llmFetchStart`, `getSettings`, `requestPermissions`, ...); Kotlin → JS via a single JSON message to `window.__andagent.receive(json)`, with event types such as `llm.headers/chunk/chunks/done/error`, `tool.result`, `permission.result`, and `notice`.

## Embedded Termux userland

`termux_setup` installs `app/src/main/assets/bootstrap/bootstrap-aarch64.zip` into the
app-private `files/usr` directory. The archive was derived from the official bootstrap
by rewriting text file paths only: `/data/data/com.termux` →
`/data/user/0/com.dc.andagent`. ELF binaries are byte-for-byte unchanged.
Current archive SHA-256: `62a92f2f6c9433f721232b24f11be2d09b76149608122694364a6957f2d69809`.
Original official archive SHA-256: `5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae`.

Installation validates dash and bash as the ready gate; `pkg list-installed` is a
soft probe reported in the backend status, so a broken apt/dpkg no longer disables
the shell backend. `shell_exec` tries the persistent bash session, direct exec,
memfd and Shizuku in order, then falls back to the Termux App bridge; each failure
carries the underlying stderr. ELF files still contain the official compile-time
paths, so `TermuxEnv` writes an `APT_CONFIG` file that redirects every apt
directory to the embedded prefix (including `Acquire::https::CaInfo` → the bundled
`cert.pem` and `DPkg::Options` `--instdir`/`--admindir`), sets `DPKG_ADMINDIR` and
`SSL_CERT_FILE`/`CURL_CA_BUNDLE`, and creates the `apt/lists`, `apt/archives`,
`sources.list.d` and solver directories the bootstrap archive omits. Because `.deb`
archives hardcode `/data/data/com.termux/...` install paths, dpkg unpacks through a
symlinked shadow dir (`files/data/data/com.termux/files/usr` → the embedded prefix).
`apt update`, `pkg install` and `pkg files` have been verified end to end on a device.
`./gradlew downloadBootstrap` verifies the official archive hash and runs
`scripts/rewrite_termux_bootstrap.py`. After updating the upstream version, refresh both
the official and derived hashes and re-check the residual-path list.
This maintenance task depends on GNU `sha256sum`, so macOS needs coreutils installed;
normal builds always package the checked-in archive and do not need this task.
The full porting record and residual path list are in `/tmp/andagent-r5-run.md`.

## Third-party components and licenses

AndAgent is licensed under [Apache-2.0](LICENSE). Third-party attribution:

- `THIRD-PARTY-NOTICES.md` — consolidated component versions, licenses, sources, and copyright notices
- `ATTRIBUTION.md` — Termux code adaptation (MIT / Apache-2.0 / GPLv3 notes) and the derived bootstrap
- `app/libs/README.md` — QuickJS Android wrapper 3.2.0 (Apache-2.0) and per-ABI SHA-256 hashes
- `app/src/main/assets/dbgui/THIRD_PARTY_LICENSES.txt` — sql.js, Grid.js
- `app/src/main/assets/vendor/scrcpy-LICENSE.txt` — scrcpy server 2.7

Progress, backlog, and milestones are tracked in `ROADMAP-TODO.md`.

## Screenshots

Screenshots will be added after final device capture.

## Star history

Star history chart will be added after the public release.

## Contributors

[GitHub automatically lists contributors](https://github.com/congduan/andagent/graphs/contributors). Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

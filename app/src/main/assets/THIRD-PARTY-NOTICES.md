# Third-party notices

AndAgent is licensed under Apache-2.0 (see [LICENSE](LICENSE)). The entries below identify the principal directly packaged or build-time third-party components. Each upstream license and copyright notice remains authoritative. Transitive npm packages are pinned in `web/package-lock.json`; Android transitive dependencies are resolved by Gradle.

## Web packages

### @assistant-ui/react 0.15.32
- License: MIT
- Source: https://github.com/assistant-ui/assistant-ui
- Copyright: Copyright (c) 2026 AgentbaseAI Inc.

### @assistant-ui/react-markdown 0.14.24
- License: MIT
- Source: https://github.com/assistant-ui/assistant-ui
- Copyright: Copyright (c) 2026 AgentbaseAI Inc.

### @mariozechner/pi-ai 0.73.1
- License: MIT
- Source: https://github.com/badlogic/pi-mono
- Copyright: Mario Zechner and contributors

### @opentelemetry/api 1.10.0
- License: Apache-2.0
- Source: https://github.com/open-telemetry/opentelemetry-js
- Copyright: OpenTelemetry contributors

### echarts 5.6.0
- License: Apache-2.0
- Source: https://github.com/apache/echarts
- Copyright: Apache Software Foundation and ECharts contributors

### gridjs 6.2.0
- License: MIT
- Source: https://github.com/grid-js/gridjs
- Copyright: Copyright (c) Afshin Mehrabani <afshin.meh@gmail.com>

### highlight.js 11.12.0
- License: BSD-3-Clause
- Source: https://github.com/highlightjs/highlight.js
- Copyright: Copyright (c) 2006, Ivan Sagalaev.

### katex 0.16.47
- License: MIT
- Source: https://github.com/KaTeX/KaTeX
- Copyright: Copyright (c) 2013-2020 Khan Academy and other contributors

### mermaid 11.17.2
- License: MIT
- Source: https://github.com/mermaid-js/mermaid
- Copyright: Copyright (c) 2014 - 2022 Knut Sveidqvist

### react 19.3.1
- License: MIT
- Source: https://github.com/facebook/react
- Copyright: Copyright (c) Meta Platforms, Inc. and affiliates.

### react-dom 19.3.1
- License: MIT
- Source: https://github.com/facebook/react
- Copyright: Copyright (c) Meta Platforms, Inc. and affiliates.

### react-markdown 10.1.0
- License: MIT
- Source: https://github.com/remarkjs/react-markdown
- Copyright: Copyright (c) Espen Hovlandsdal

### rehype-katex 7.0.1
- License: MIT
- Source: https://github.com/remarkjs/remark-math
- Copyright: Junyoung Choi and contributors

### remark-gfm 4.0.1
- License: MIT
- Source: https://github.com/remarkjs/remark-gfm
- Copyright: Copyright (c) Titus Wormer <tituswormer@gmail.com>

### remark-math 6.0.0
- License: MIT
- Source: https://github.com/remarkjs/remark-math
- Copyright: Junyoung Choi and contributors

### sql.js 1.15.2
- License: MIT
- Source: https://github.com/sql-js/sql.js
- Copyright: Copyright (c) 2017 sql.js authors (see AUTHORS)

## Android and bundled binaries

### AndroidX Compose (BOM 2024.10.01), Material 3, and Material Icons
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/compose
- Copyright: The Android Open Source Project

### AndroidX Navigation Compose 2.8.4
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/navigation
- Copyright: The Android Open Source Project

### AndroidX Activity 1.9.3
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/activity
- Copyright: Copyright Android Open Source Project contributors

### AndroidX WebKit 1.12.1
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/webkit
- Copyright: Copyright Android Open Source Project contributors

### AndroidX Core 1.13.1
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/core
- Copyright: Copyright Android Open Source Project contributors

### AndroidX WorkManager 2.9.1
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/work
- Copyright: Copyright Android Open Source Project contributors

### AndroidX Health Connect Client 1.1.0-alpha07
- License: Apache-2.0
- Source: https://developer.android.com/jetpack/androidx/releases/health-connect
- Copyright: Copyright Android Open Source Project contributors

### OkHttp 4.12.0
- License: Apache-2.0
- Source: https://github.com/square/okhttp
- Copyright: Copyright Square, Inc. and contributors

### Kotlinx Serialization JSON 1.7.3
- License: Apache-2.0
- Source: https://github.com/Kotlin/kotlinx.serialization
- Copyright: Copyright JetBrains s.r.o. and contributors

### Kotlinx Coroutines Android 1.9.0
- License: Apache-2.0
- Source: https://github.com/Kotlin/kotlinx.coroutines
- Copyright: Copyright JetBrains s.r.o. and contributors

### QuickJS wrapper Android and Java 3.2.0
- License: Apache-2.0 (publisher POM)
- Source: https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/3.2.0/wrapper-android-3.2.0.pom
- Copyright: wang.harlon.quickjs publisher and upstream contributors; see app/libs/README.md for vendored binary hashes

### ZXing Core 3.5.3
- License: Apache-2.0
- Source: https://github.com/zxing/zxing
- Copyright: Copyright ZXing authors and contributors; see app/libs/README.md for vendored JAR hash

### scrcpy server 2.7
- License: Apache-2.0
- Source: https://github.com/Genymobile/scrcpy
- Copyright: Copyright Genymobile and scrcpy contributors; full license in app/src/main/assets/vendor/scrcpy-LICENSE.txt

### Termux terminal-emulator / terminal-view 0.118.1 (vendored source)
- License: Apache-2.0 (explicit exception in upstream `termux-app/LICENSE.md`; the rest of `termux-app` is GPLv3-only and was not copied)
- Source: https://github.com/termux/termux-app/tree/v0.118.1
- Copyright: Termux contributors (Fredrik Fornwall and contributors). Upstream files carry no per-file copyright header; the notice is recorded in root `ATTRIBUTION.md`.
- Vendored into `app/src/main/java/com/termux/terminal/`, `app/src/main/java/com/termux/view/`, `app/src/main/cpp/termux.c` (`libtermux.so`) and `app/src/main/res/{values,values-zh,drawable}/`. Full file list, the two `com.termux.view.R` → `com.dc.andagent.R` import changes, and the five same-tag files added to complete the dependency graph are recorded in root `ATTRIBUTION.md`.

### Termux bootstrap (derived, version identified by archive SHA-256)
- License: Mixed; package-specific notices inside archive
- Source: https://github.com/termux/termux-packages
- Copyright: Copyright Termux package maintainers and upstream authors; see ATTRIBUTION.md and archive notices

### Flipper-IRDB（内置红外码表数据，app/src/main/assets/ir/ir-codes.json）
- License: CC0 1.0 Universal（公共领域贡献，无署名/告知/提供副本义务）
- Source: https://github.com/Lucaslhm/Flipper-IRDB （取自 TVs/{LG,Samsung,Sony,Hitachi,Sanyo,Hisense,Toshiba,TCL,Haier}、ACs/Midea、Projectors/Wanbo 下的 parsed .ir 文件，经 scripts/gen_ir_codes.py 归一化为协议参数；完整清单见 assets/ir/ir-codes.json 各 device 的 source 字段）
- Copyright: Flipper-IRDB contributors（CC0 1.0）
- 注：仅保留来源 URL 以便追溯，非许可要求。

### esptool flasher stub binaries（app/src/main/assets/esp_stub/*.bin）
- License: Apache-2.0 OR MIT（dual license；全文见 app/src/main/assets/esp_stub/LICENSE-APACHE 与 LICENSE-MIT）
- Source: https://github.com/espressif/esptool — `esptool/targets/stub_flasher/2/*.json`（set 2，来自 https://github.com/espressif/esp-flasher-stub/releases/tag/v1.4.1）
- Copyright: Copyright 2024-2026 Espressif Systems (Shanghai) CO LTD and esp-flasher-stub contributors
- 注：本目录的 .bin 是把官方 JSON 里的 base64 `text`/`data` 解码后，加上 36 字节元数据头（magic `ESTB`、schema version、entry、text_start/text_len、data_start/data_len、chip_id、stub 版本标签 0x00010401）重新封装的容器，未修改固件本体；各文件来源 URL 与 SHA-256 见 /tmp/andagent-r143-run.md。

## Build-only frontend tool

### Vite 6.4.5
- License: MIT
- Source: https://github.com/vitejs/vite
- Copyright: Copyright (c) 2019-present Evan You and Vite contributors

The complete dependency versions are recorded in `web/package-lock.json` and `app/build.gradle.kts`. sql.js and Grid.js full license texts are also shipped in `app/src/main/assets/dbgui/THIRD_PARTY_LICENSES.txt`.

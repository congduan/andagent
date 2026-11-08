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

### Termux bootstrap (derived, version identified by archive SHA-256)
- License: Mixed; package-specific notices inside archive
- Source: https://github.com/termux/termux-packages
- Copyright: Copyright Termux package maintainers and upstream authors; see ATTRIBUTION.md and archive notices

### irdb（内置红外码表数据）
- License: irdb permission license（非 OSI）；条款见 https://github.com/probonopd/irdb/blob/master/LICENSE.md
- Source: https://github.com/probonopd/irdb （数据取自 codes/LG/TV/4,-1.csv、codes/Samsung/TV/7,7.csv、codes/Sony/TV/1,-1.csv、codes/Philips/TV/0,-1.csv，归一化为 app/src/main/assets/ir/ir-codes.json 的协议参数）
- Notice（许可证要求原文）: `Contains/accesses irdb by Simon Peter and contributors, used under permission. For licensing details and for information on how to contribute to the database, see https://github.com/probonopd/irdb`
- Copyright: Copyright (c) 2013-15 Simon Peter and contributors
- 待办：许可证条款 1 要求「在把数据库用于产品前，通过 issue 告知 irdb 项目」，公开发布前须在 https://github.com/probonopd/irdb/issues 开一条说明（跟踪于 ROADMAP-TODO R113）

## Build-only frontend tool

### Vite 6.4.5
- License: MIT
- Source: https://github.com/vitejs/vite
- Copyright: Copyright (c) 2019-present Evan You and Vite contributors

The complete dependency versions are recorded in `web/package-lock.json` and `app/build.gradle.kts`. sql.js and Grid.js full license texts are also shipped in `app/src/main/assets/dbgui/THIRD_PARTY_LICENSES.txt`.

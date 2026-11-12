# Termux attribution

Source reviewed: [termux/termux-app](https://github.com/termux/termux-app), commit `8629e632fcb95da272221be327db653fb24befe9`.

- `AndagentTermuxPaths.kt`: adapted minimal path and environment concepts from `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`, MIT. Copyright Termux contributors.
- `TermuxSession.kt`: adapted long lived process and stream handling concepts from `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`, Apache-2.0. Copyright Termux contributors. The implementation uses pipes and has no terminal emulator classes.
- `TermuxBootstrapInstaller.kt`: independent implementation of the bootstrap ZIP and `SYMLINKS.txt` format. `app/src/main/java/com/termux/app/TermuxInstaller.java` was reviewed for format compatibility but its GPLv3-only source was not copied.
- `bootstrap-aarch64.zip`: derivative of the official Termux bootstrap archive. Text absolute paths were changed for the andagent package; ELF files were not altered. Package license notices remain in the archive.

The upstream `termux-shared` module is generally MIT with exceptions; `TermuxConstants.java` is explicitly MIT. The `termux-app` repository defaults to GPLv3-only; `terminal-emulator` is an Apache-2.0 exception. See upstream `LICENSE.md` and `termux-shared/LICENSE.md`.

## R83① vendored Termux terminal-emulator / terminal-view (v0.118.1)

Source: [termux/termux-app](https://github.com/termux/termux-app), tag **v0.118.1** (the same tag as the reviewed commit above). `terminal-emulator` and `terminal-view` are the two modules explicitly licensed **Apache-2.0** in upstream `LICENSE.md`; the rest of `termux-app` is GPLv3-only and was **not** copied. Copyright: Termux contributors (Fredrik Fornwall and contributors). The upstream source files themselves carry no per-file copyright header, so the copyright and license notice are recorded here in one place; the full license text is Apache-2.0 (see [LICENSE](LICENSE) and `THIRD-PARTY-NOTICES.md`).

All Java/C files below are byte-identical to tag `v0.118.1` unless listed under "Modifications". They were verified against the upstream tag with `diff -q` before vendoring.

### `app/src/main/java/com/termux/terminal/` — module `terminal-emulator` (14 files)

`ByteQueue.java`, `JNI.java`, `KeyHandler.java`, `Logger.java`, `TerminalBuffer.java`, `TerminalColorScheme.java`, `TerminalColors.java`, `TerminalEmulator.java`, `TerminalOutput.java`, `TerminalRow.java`, `TerminalSession.java`, `TerminalSessionClient.java`, `TextStyle.java`, `WcWidth.java`

### `app/src/main/java/com/termux/view/` — module `terminal-view` (4 files)

`TerminalView.java`, `TerminalViewClient.java`, `TerminalRenderer.java`, `GestureAndScaleRecognizer.java`

### `app/src/main/java/com/termux/view/textselection/` — module `terminal-view` (3 files)

`CursorController.java`, `TextSelectionCursorController.java`, `TextSelectionHandleView.java`

### Native + resources

- `app/src/main/cpp/termux.c` — upstream `terminal-emulator/src/main/jni/termux.c` (214 lines). JNI exports `Java_com_termux_terminal_JNI_*`; built as `libtermux.so` via `app/src/main/cpp/CMakeLists.txt`.
- `app/src/main/res/drawable/text_select_handle_left_material.xml` and `text_select_handle_right_material.xml` — upstream `terminal-view/.../drawable/` vector drawables (staged as `h-left.xml` / `h-right.xml`).
- `app/src/main/res/values/terminal_strings.xml` — upstream `terminal-view/.../values/strings.xml` keys `copy_text`, `paste_text`, `text_selection_more`, plus three new R83① UI strings. `values-zh/terminal_strings.xml` adds Chinese translations. No existing app resource keys were overwritten (there is no `values/strings.xml` in this app).
- `TextSelectionHandleView` uses `android.R.attr.textSelectHandleWindowStyle`; upstream has no `attrs.xml`, so no custom attr resources were added.

### Modifications (only two, both required for compilation in this app's namespace)

The `terminal-view` sources import `com.termux.view.R` because upstream builds them as a separate library module. Here they live in the app's main source set, whose generated R class is `com.dc.andagent.R`. Per Apache-2.0 §4(b) these changes are recorded explicitly; neither changes runtime behaviour.

1. `app/src/main/java/com/termux/view/textselection/TextSelectionHandleView.java` line 15: `import com.termux.view.R;` → `import com.dc.andagent.R;`. Its two `R.drawable.*` references (and the three `R.string.*` references in the other file) resolve through the app R class unchanged.
2. `app/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java` line 17: same import change.

No other vendored file was modified. No new Gradle dependency was added (source-vendored only).

### Files added from the same tag to complete the upstream dependency graph

The initial source drop omitted five classes referenced by the staged files; they were fetched from **the same v0.118.1 tag** and vendored unmodified. Without them the library does not compile. This is an addition, not a behaviour change: `TerminalViewClient.java` (interface used by `TerminalView`), `TerminalRenderer.java` (renderer used by `TerminalView`), `Logger.java`, `ByteQueue.java`, `TerminalColorScheme.java` (used by `TerminalSession` / `TerminalColors`).

### R83① integration note

`com.dc.andagent.terminal.TerminalActivity` creates an independent real-pty `TerminalSession` (`cmd` = embedded bash, `cwd` = embedded home, `env` = `AndagentTermuxPaths.environment` plus `TERM=xterm-256color`). It does **not** share state with the R27 pipe-based `TermuxEnv` session. `TERM` is required because `JNI.createSubprocess()` calls `clearenv()` and only `putenv()`s the supplied array; without it TUI programs cannot start.

## R147 terminal UI (font + color scheme)

Additional modification on top of the v0.118.1 vendoring above, recorded per Apache-2.0 §4(b):

- `app/src/main/java/com/termux/terminal/TerminalColorScheme.java` — the `DEFAULT_COLORSCHEME` constant table was edited:
  - the first 16 ANSI colors were replaced with the One Dark Pro palette to match the app's `#0B0D11` theme;
  - the default foreground slot (`TextStyle.COLOR_INDEX_FOREGROUND` = index 256) was changed to `#abb2bf`. There is no hardcoded `mDefaultForeground` in `TerminalEmulator`; this palette slot is the default, and the OSC 10 / OSC 110 override-and-reset path is untouched.
  - the 216-color cube, the 24-step grey ramp, the default background (index 257), and the cursor (index 258) are unchanged from upstream; the shell can still override colors via OSC 4 / OSC 10.
  - No JNI, emulator, or renderer logic was changed.

- `app/src/main/assets/fonts/` — JetBrains Mono 2.304 (Regular + Bold TTF) and its `OFL.txt` (SIL Open Font License 1.1) were bundled from the official release `JetBrainsMono-2.304.zip`. License and source are recorded in `app/src/main/assets/THIRD-PARTY-NOTICES.md`. SHA-256 (official release, verified against APK contents): Regular `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f`, Bold `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb`, OFL.txt `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`. The Regular face is attached via the public `TerminalView.setTypeface(Typeface)` API; bold rendering continues to use the renderer's single-paint fake-bold, so no renderer code was modified.

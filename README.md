# Termux Kotlin

Fork of [termux/termux-app](https://github.com/termux/termux-app) — entire UI rewritten in Kotlin + Jetpack Compose.

## What's different

- **100% Kotlin** — all production code across all 4 modules (app, terminal-emulator, terminal-view, termux-shared)
- **Jetpack Compose UI** — main screen, session drawer, extra keys, toolbar, context menu, settings — all Compose Material 3
- **Material You** — dynamic colors (Monet) + custom "Termux Colors" mode derived from terminal color scheme
- **Material 3 Expressive** shapes in settings and UI elements
- **TAPI / Nightzuku** — `kerneldroid.nightzuku.TAPI_SUPPORT` for Nightzuku integration
- **Full plugin compatibility** — Termux:API, Boot, Float, Styling, Tasker, Widget

Terminal rendering remains a native View (`TerminalView`) embedded via `AndroidView` — correct approach for Canvas-based cell rendering.

## Build

```bash
./gradlew assembleRelease
```

Requires signing config in `local.properties` (not tracked). See `local.properties.example`.

## Stack

Kotlin 2.4.0 · Compose BOM 2026.06.00 · AGP 9.3.0 · compileSdk 37 · minSdk 24

## License

[GPLv3](LICENSE.md) · Based on [Termux](https://termux.dev)

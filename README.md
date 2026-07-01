# Termux (Kotlin + Compose Rewrite)

Fork of [termux/termux-app](https://github.com/termux/termux-app) with the entire UI rewritten in **Kotlin** and **Jetpack Compose**.

## What Changed

- **Full Kotlin rewrite** — all production code converted from Java to Kotlin
- **Jetpack Compose UI** — main activity, menus, toolbar, settings, and context menus rebuilt with Compose Material 3
- **TAPI / Nightzuku compatibility** — meta-data tag `kerneldroid.nightzuku.TAPI_SUPPORT` for integration with [Nightzuku](https://github.com/kerneldroid) via Termux API
- **Modern build toolchain** — Kotlin 2.4.0, Compose BOM 2025.02.00, AGP 9.3.0, compileSdk 37

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config in local.properties)
./gradlew assembleRelease

# Universal APK
./gradlew assembleRelease -Pandroid.injected.build.api=universal
```

### Signing

Copy `local.properties.example` to `local.properties` and fill in your keystore credentials:

```properties
TERMUX_RELEASE_STORE_FILE=/path/to/your.keystore
TERMUX_RELEASE_STORE_PASSWORD=your_password
TERMUX_RELEASE_KEY_ALIAS=your_alias
TERMUX_RELEASE_KEY_PASSWORD=your_password
```

## Termux App and Plugins

Core Termux works with optional plugin apps:

- [Termux:API](https://github.com/termux/termux-api)
- [Termux:Boot](https://github.com/termux/termux-boot)
- [Termux:Float](https://github.com/termux/termux-float)
- [Termux:Styling](https://github.com/termux/termux-styling)
- [Termux:Tasker](https://github.com/termux/termux-tasker)
- [Termux:Widget](https://github.com/termux/termux-widget)

## Installation

Download the APK from [Releases](../../releases) and install it.

For package management inside Termux, see [termux/termux-packages](https://github.com/termux/termux-packages).

## License

This project is licensed under the [GPLv3](LICENSE.md).

Based on [Termux](https://termux.dev) by the Termux community.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The Mapbox AI Voice Navigation Sample App: an Android and an iOS sample that pair a Mapbox navigation map with a GPT-Live voice assistant whose tools act on the map. Each sample app includes all the code needed to run it and depends on its platform's library.

The tool list in the root `README.md` is the reference for both platforms; update it when tools are added, removed, or renamed. Keep the root `README.md` platform neutral, and put platform-specific details, such as each sample's local tools, in `android/README.md` or `ios/README.md`.

## Android (`android/`)

Gradle project with two modules: `toolkit` (the SDK library, minSdk 24) and `app` (the sample, minSdk 28 for WebRTC). JDK 17, versions in `gradle/libs.versions.toml`.

Credentials: `MAPBOX_ACCESS_TOKEN` and `OPENAI_API_KEY` in `android/local.properties`; `MAPBOX_DOWNLOADS_TOKEN` in `~/.gradle/gradle.properties`, an environment variable, or `local.properties` (required to resolve the Mapbox dependencies at all). The OpenAI key is embedded only in debug builds.

```sh
cd android
./gradlew assembleDebug
```

Architecture: `NavigationActions` (one per app) builds the `nav_sdk_*`, place-details, and home/work tools, holds their shared state, and `attach()`es to the UX Framework's `DashNavigationFragment`. `NavigationActions.liveDirector(actions)` returns a `LiveDirector`, the GPT-Live agent loop: it advertises tools, runs tool calls, pushes context updates, and always adds `end_conversation`. Its prompts are resources in `toolkit/src/main/resources/com/mapbox/toolkit/live/*.md`. The app owns the realtime connection (`app/.../live/LiveSessionClient`) and forwards server events to the director session. Custom tools are `ViaAction`/`ViaNoArgsAction` (see `app/.../tools/DeviceTimeAction.kt`).

## iOS (`ios/`)

It showcases an alternative design from Android: the assistant relies on more **Mapbox MCP server's** tools, and the app renders results differently. Both paradigms are correct, and the app developer can choose the one that better fits the application use case.

- `ios/Example/Example.xcodeproj` — SwiftUI sample (MCP client, tool-result parsing, `VoiceAudio` for mic/playback, `MainViewModel`). Tests in `ExampleTests/`. Tokens: secret downloads token in `~/.netrc`; public token in `~/.mapbox` (injected by `Scripts/apply-mapbox-access-token.sh`); OpenAI key in `~/.openai` or `OPENAI_API_KEY` (Debug builds only).
- `ios/MapboxNavigationAgent/` — Swift wrapper the Example imports. `Package.swift` downloads ViaKit and the wrapper is the only target that imports that module. `LiveVoiceSession` forwards to ViaKit's `ViaVoiceSession`. The voice floor and the MCP client stay in the wrapper.

In Swift, refer to the local tools via `MapboxVoiceSession.endConversationToolName` and `MapboxVoiceSession.stopNavigationToolName`. Keep their descriptions at least four sentences, in snake_case, and only mention tools the session offers.

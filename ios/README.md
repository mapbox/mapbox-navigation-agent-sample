# Mapbox AI Voice Navigation Sample App for iOS

This folder is the iOS version of the Mapbox AI Voice Navigation Sample App. Like the rest of this repository, it's in Public Preview. See [LICENSE.md](../LICENSE.md) for details.

The iOS sample takes a different approach from the Android one. Instead of tools that run inside the app, the voice assistant uses the tools of the [Mapbox MCP server](https://docs.mapbox.com/location-ai/mcp-servers/mcp-server/), and the app shows their results on the map. It has two parts:

- **`Example`** is the sample app: the map, turn-by-turn guidance, and the voice button. It follows the Android sample's naming, so `Example` matches Android's `app` module, and `MainViewModel` holds the voice session state on both platforms. It also contains the Swift client: the MCP client, typed parsing of the server's tool results, and `MapboxToolLog`, which pairs each tool call with its result.
- **`MapboxNavigationAgent`** is the Swift wrapper the Example imports. It downloads ViaKit (`ViaKit.xcframework` from `mapbox-navigation-agent-ios`) and keeps that module inside the package. The Example does not import `ViaKit`. Voice goes through ViaKit's `ViaVoiceSession`.

## Run the sample app

Open `Example/Example.xcodeproj` in Xcode 27 and run the Example scheme on a device or simulator with iOS 27 or later. The bundle id is `com.mapbox.toolkit.example`, the same as the Android app. Set `DEVELOPMENT_TEAM` to your own team before you sign a build.

The voice button spans the bottom of the screen and reads Start, Connecting, Listening, or Stopping. The app asks for microphone and location access when it opens, and Start stays disabled until both are granted. Each tool call shows its name in a banner at the top of the map. In Debug builds, a list button at the top right opens the full tool-call log, with each call's arguments and result.

### Use the app

The user's location is simulated, starting at the Ferry Building in San Francisco, so you can try routes on the simulator. The voice guide treats that spot as "here" in every tool call. The app still asks for location access, but it doesn't use the device's real location.

What the app does with each kind of result:

- **Places.** Search, category, reverse geocoding, and `ground_location_tool` results are pinned on the map, and the map frames the pins. When a search returns some results within 100 km and others farther away, the far ones are dropped.
- **Place card.** Tapping a pin opens a card with the place's photo, today's hours, phone, and website from `place_details_tool`, a walking, driving, or cycling route preview, and a Start button.
- **Directions.** A `directions_tool` result selects its destination, matches its travel mode, and starts turn-by-turn guidance right away, with spoken instructions. Say "stop navigation" to end the trip and keep talking, or tap End to go back to the route preview.
- **Areas, lines, and points.** Isochrone, buffer, union, intersect, difference, convex, bbox, simplify, map matching, and points-within-polygon results are drawn on the map. The point tools, such as `centroid_tool` and `midpoint_tool`, mark one point. An optimized trip is drawn as a line with numbered stops.
- **Measurements.** Distance, length, area, bearing, matrix, optimization, and category list results are summarized in the banner at the top of the map, which stays up longer than a tool name.
- **Map image.** A `static_map_image_tool` image opens in a card, which you close with its button.

Each new set of pins or shapes replaces the last one. During guidance, the banner appears below the turn instruction, and tool names aren't shown, so they don't distract the driver.

### Mapbox credentials

The app needs two tokens from a [Mapbox account](https://account.mapbox.com/access-tokens/). Neither is committed. The setup follows the [Navigation SDK install guide](https://docs.mapbox.com/ios/navigation/guides/install/).

**Secret downloads token.** Swift Package Manager uses this to download the Navigation SDK binaries. Create a secret token with the `Downloads:Read` scope. You can copy it once. Store it in `~/.netrc`, outside the project:

```
machine api.mapbox.com
login mapbox
password <secret token>
```

Then run `chmod 600 ~/.netrc`. If the file already has other machines, add these three lines to it.

**Public access token.** The SDK uses this at runtime for maps, directions, and the MCP server. `Info.plist` ships with `MBXAccessToken` set to `PASTE MAPBOX ACCESS TOKEN HERE`. The Example target's **Apply Mapbox Tokens** run script (`Scripts/apply-mapbox-access-token.sh`) replaces that placeholder in the built app from `~/.mapbox`, or from `~/mapbox` if the first file is missing. Put the public token in that file, one line, with nothing else. A build without the file warns and continues, and the map will not load. You can instead paste the token into `Info.plist` and delete the run script phase.

The MCP server's `feedback_tool` also needs the `user-feedback:write` scope on this token. Without it, sending feedback fails with a 403 error, and the other tools still work. To add the scope, edit your token, or create a new one, on the [Access tokens](https://account.mapbox.com/access-tokens/) page. If you create a new token, put it in `~/.mapbox` and rebuild the app. Each `feedback_tool` call files a real feedback item in the Mapbox account that owns the token.

### OpenAI key

Voice uses OpenAI's [GPT-Live](https://developers.openai.com/api/docs/guides/live). Create a [project API key](https://platform.openai.com/api-keys) on a paid usage tier. GPT-Live is unavailable on the free tier, and voice time is billed to that account. The Example has no Settings screen for the key. Put it in `~/.openai` before you build, as a single line with nothing else.

On a Debug build, the **Apply Mapbox Tokens** script (`Scripts/apply-mapbox-access-token.sh`) reads `~/.openai` and writes the key into the built app's `Info.plist` as `OpenAIAPIKey`, in plain text. Release and archive builds skip this and do not contain the key. Do not ship a Debug build, and do not commit the key. `MapboxVoiceSession` also accepts the key as a parameter, which is what the app passes at runtime.

## Audio and tools

The Example target's `VoiceAudio` handles microphone capture and assistant playback with `AVAudioEngine`. ViaKit is the published framework the Example links. It doesn't own the audio devices.

The model sees the MCP server's tools exactly as the server describes them, plus two tools the session runs itself:

- `end_conversation` ends the voice session when the user is done. It has the same name as on Android.
- `stop_navigation` ends turn-by-turn guidance and keeps the voice session open.

When your instructions mention them, use the `MapboxVoiceSession.endConversationToolName` and `MapboxVoiceSession.stopNavigationToolName` constants instead of the literal names.

## Framework package

Swift Package Manager downloads ViaKit `0.1.0-alpha.4` from `https://api.mapbox.com/downloads/v2/mapbox-navigation-agent-ios/releases/ios/packages/0.1.0-alpha.4/ViaKit.xcframework.zip`. The secret downloads token in `~/.netrc` is the same token the Navigation SDK uses. `MapboxNavigationAgent/Package.swift` wraps that binary. `LiveVoiceSession` forwards to ViaKit's `ViaVoiceSession`. When a tool result lists places, the Example shows them on the map. The voice session does not receive that list.

The sample's instructions (`VoiceGuide` in `MainViewModel.swift`) tell the model which server tool to use for each kind of request, and never to call `render_map_tool`, because the app draws results itself. `VoiceGuideTests` checks that every tool they name is one the session offers.

## Run the tests

The Example tests cover tool-result parsing, the shapes and summaries the map shows, the place filter, and the voice guide. In Xcode 27, run them with ⌘U, or from this folder:

```sh
xcodebuild test -project Example/Example.xcodeproj -scheme Example \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0' CODE_SIGNING_ALLOWED=NO
```

Replace `iPhone 17` with any iPhone simulator that runs iOS 27. To list them, run `xcodebuild -project Example/Example.xcodeproj -scheme Example -showdestinations`. `CODE_SIGNING_ALLOWED=NO` runs the tests without a development team.

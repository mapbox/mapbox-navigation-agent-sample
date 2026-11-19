# This script helps to keep the navigation SDK’s developers from exposing their own access tokens during development.
# See <https://www.mapbox.com/help/ios-private-access-token/> for more information. If you are developing an application privately,
# you may add the MBXAccessToken key directly to your Info.plist file and delete Apply Mapbox Access Token
# Run Script Phase in Build Phases.
token_file=~/.mapbox
token_file2=~/mapbox
token="$(cat "$token_file" 2>/dev/null || cat "$token_file2" 2>/dev/null)"
if [ "$token" ]; then
  plutil -replace MBXAccessToken -string "$token" "$TARGET_BUILD_DIR/$INFOPLIST_PATH"
else
  echo "warning: Missing Mapbox access token"
  echo "warning: Get an access token from <https://www.mapbox.com/account/access-tokens/>, then create a new file at $token_file or $token_file2 that contains the access token."
fi

# Debug builds only. The key is written into the built app's Info.plist in plain text, so a Release
# or archived build must not carry it. Pass a key at runtime instead of shipping one.
if [ "$CONFIGURATION" = "Debug" ]; then
  openai_key="${OPENAI_API_KEY:-}"
  if [ -z "$openai_key" ] && [ -f "$HOME/.openai" ]; then
    openai_key="$(tr -d '[:space:]' < "$HOME/.openai")"
  fi
  if [ -n "$openai_key" ]; then
    plutil -replace OpenAIAPIKey -string "$openai_key" "$TARGET_BUILD_DIR/$INFOPLIST_PATH"
  fi
fi

#!/usr/bin/env bash
# Usage: ./release.sh 0.2.1 "What changed"
# Bumps the version, builds a signed APK, tags, pushes and publishes a GitHub release.
# Installed apps see the new release via the in-app updater.
set -euo pipefail
V="${1:?version like 0.2.1}"; NOTES="${2:-}"
IFS=. read -r A B C <<< "$V"
CODE=$((A * 10000 + B * 100 + C))
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $CODE/; s/versionName = \"[^\"]+\"/versionName = \"$V\"/" app/build.gradle.kts
./gradlew -q assembleRelease
APK="build/HyperShare-v$V.apk"; mkdir -p build; cp app/build/outputs/apk/release/app-release.apk "$APK"
git add app/build.gradle.kts && git commit -qm "Release v$V" && git tag "v$V" && git push -q && git push -q --tags
gh release create "v$V" "$APK" --title "HyperShare v$V" --notes "${NOTES:-Release v$V}"

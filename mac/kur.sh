#!/bin/zsh
# AgentBridge.app'i yeniden üretir ve /Applications'a kurar (yerli pencere: main.swift, ikon: AgentBridge.icns).
# Adres (token dahil) git'e girmeyen adres.local'dan okunur ve Info.plist'e ABAdres olarak gömülür.
set -e
cd "${0:A:h}"
APP=/Applications/AgentBridge.app
[[ -s adres.local ]] || { echo "adres.local yok (içine köprü URL'si + #token=… yaz)" >&2; exit 1; }
ADRES=$(tr -d '\n' < adres.local)
TMP=$(mktemp -d)

swiftc -O -swift-version 5 -o "$TMP/AgentBridge" main.swift -framework Cocoa -framework WebKit

# açık kopya varsa kapat (çalışan ikiliyi değiştirmeyelim)
osascript -e 'tell application id "com.agent.bridge.web" to quit' >/dev/null 2>&1 || true
sleep 1
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$TMP/AgentBridge" "$APP/Contents/MacOS/AgentBridge"
cp AgentBridge.icns "$APP/Contents/Resources/AgentBridge.icns"
cat > "$APP/Contents/Info.plist" <<P
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key><string>AgentBridge</string>
	<key>CFBundleDisplayName</key><string>AgentBridge</string>
	<key>CFBundleIdentifier</key><string>com.agent.bridge.web</string>
	<key>CFBundleExecutable</key><string>AgentBridge</string>
	<key>CFBundleIconFile</key><string>AgentBridge</string>
	<key>CFBundlePackageType</key><string>APPL</string>
	<key>CFBundleDevelopmentRegion</key><string>tr</string>
	<key>CFBundleShortVersionString</key><string>2.0</string>
	<key>CFBundleVersion</key><string>2</string>
	<key>LSMinimumSystemVersion</key><string>13.0</string>
	<key>NSHighResolutionCapable</key><true/>
	<key>LSApplicationCategoryType</key><string>public.app-category.developer-tools</string>
	<key>ABAdres</key><string>${ADRES//&/&amp;}</string>
	<key>NSAppTransportSecurity</key>
	<dict>
		<key>NSAllowsLocalNetworking</key><true/>
		<key>NSAllowsArbitraryLoads</key><true/>
	</dict>
</dict>
</plist>
P
codesign --force -s - "$APP" >/dev/null 2>&1
touch "$APP"
rm -rf "$TMP"
echo "hazır: $APP  ($(du -sh "$APP" | cut -f1))"

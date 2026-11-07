#!/bin/zsh
# Telekumanda.app'i derler ve /Applications'a kurar; simge docs/logo/yorunge.svg dosyasından üretilir.
# Köprü adresi git dışı adres.local dosyasından okunur (yalnız adres), token arayüzde ilk açılışta girilir.
set -e
cd "${0:A:h}"
KOK=${0:A:h:h}
APP=/Applications/Telekumanda.app
TMP=$(mktemp -d)

# adres.local yalnız köprü adresini içerir; query ve fragment atılır, dosya yoksa varsayılan kullanılır
ADRES=""
if [[ -s adres.local ]]; then
  ADRES=$(tr -d '\r\n' < adres.local | sed -e 's/[?#].*//' -e 's/[[:space:]]*$//')
else
  echo "adres.local yok (dosya yalnız köprü adresini içerir); varsayılan adres kullanılacak: http://127.0.0.1:8787/ui/" >&2
fi

swiftc -O -swift-version 5 -o "$TMP/Telekumanda" main.swift -framework Cocoa -framework WebKit

# simge: SVG -> iconset PNG'leri -> icns
swift ikon.swift "$KOK/docs/logo/yorunge.svg" "$TMP/Telekumanda.iconset"
iconutil -c icns "$TMP/Telekumanda.iconset" -o "$TMP/Telekumanda.icns"

# açık kopya varsa kapat (çalışan ikiliyi değiştirmeyelim)
osascript -e 'tell application id "com.agent.bridge.web" to quit' >/dev/null 2>&1 || true
sleep 1
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$TMP/Telekumanda" "$APP/Contents/MacOS/Telekumanda"
cp "$TMP/Telekumanda.icns" "$APP/Contents/Resources/Telekumanda.icns"

ABADRES=""
if [[ -n $ADRES ]]; then
  ABADRES="	<key>ABAdres</key><string>${ADRES//&/&amp;}</string>"
fi

cat > "$APP/Contents/Info.plist" <<P
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key><string>Telekumanda</string>
	<key>CFBundleDisplayName</key><string>Telekumanda</string>
	<key>CFBundleIdentifier</key><string>com.agent.bridge.web</string>
	<key>CFBundleExecutable</key><string>Telekumanda</string>
	<key>CFBundleIconFile</key><string>Telekumanda</string>
	<key>CFBundlePackageType</key><string>APPL</string>
	<key>CFBundleDevelopmentRegion</key><string>tr</string>
	<key>CFBundleShortVersionString</key><string>3.0</string>
	<key>CFBundleVersion</key><string>3</string>
	<key>LSMinimumSystemVersion</key><string>13.0</string>
	<key>NSHighResolutionCapable</key><true/>
	<key>LSApplicationCategoryType</key><string>public.app-category.developer-tools</string>
${ABADRES}
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

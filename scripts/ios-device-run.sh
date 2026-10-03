#!/usr/bin/env bash
# 연결된 iPhone에 iOS 앱(ios/App)을 서명 빌드해 설치하고 실행한다. macOS 전용.
#
#   scripts/ios-device-run.sh               # 연결된 첫 iPhone
#   DEVICE=<UDID 또는 이름> scripts/ios-device-run.sh
#   CONSOLE=1 scripts/ios-device-run.sh     # 실행 후 앱 로그(stdout/NSLog)를 터미널에 붙인다 (Ctrl-C로 끝)
#   CONFIGURATION=Release scripts/ios-device-run.sh
#
# 준비물은 ios/README.md "실기기에서 실행" 참고: Xcode 16+, xcodegen, Xcode에 로그인한 Apple ID(팀),
# ios/App/Config/Secrets.xcconfig의 DEVELOPMENT_TEAM, iPhone의 개발자 모드와 이 Mac 신뢰.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/ios/App"
CONFIGURATION="${CONFIGURATION:-Debug}"
DERIVED="$APP_DIR/build/DerivedData"

fail() {
    echo "error: $*" >&2
    exit 1
}

[[ "$(uname)" == "Darwin" ]] || fail "macOS에서만 실행할 수 있어요."
command -v xcodebuild >/dev/null || fail "Xcode가 없어요. App Store에서 설치하고 'sudo xcode-select -s /Applications/Xcode.app'."
command -v xcodegen >/dev/null || fail "xcodegen이 없어요: brew install xcodegen"
xcrun devicectl --version >/dev/null 2>&1 || fail "xcrun devicectl이 없어요 (Xcode 15 이상 필요)."

SECRETS="$APP_DIR/Config/Secrets.xcconfig"
if [[ ! -f "$SECRETS" ]]; then
    fail "$SECRETS 가 없어요. 'cp $APP_DIR/Config/Secrets.example.xcconfig $SECRETS' 후 DEVELOPMENT_TEAM을 채워 주세요."
fi
TEAM="$(sed -n 's/^[[:space:]]*DEVELOPMENT_TEAM[[:space:]]*=[[:space:]]*\([A-Z0-9]*\).*/\1/p' "$SECRETS" | tail -1)"
[[ -n "$TEAM" ]] || fail "Secrets.xcconfig의 DEVELOPMENT_TEAM이 비어 있어요. Xcode → Settings → Accounts의 팀 ID(10자리)를 넣어 주세요."

# 1. 프로젝트 생성
echo "==> xcodegen generate"
(cd "$APP_DIR" && xcodegen generate --quiet)

# 2. 기기 찾기 (CoreDevice). DEVICE가 있으면 그것, 없으면 연결된 첫 iPhone.
DEVICES_JSON="$(mktemp)"
trap 'rm -f "$DEVICES_JSON"' EXIT
xcrun devicectl list devices --json-output "$DEVICES_JSON" >/dev/null
read -r UDID NAME < <(
    DEVICE="${DEVICE:-}" python3 - "$DEVICES_JSON" <<'PY'
import json, os, sys

devices = json.load(open(sys.argv[1]))["result"]["devices"]
wanted = os.environ.get("DEVICE", "")

def describe(d):
    hw, props, conn = d.get("hardwareProperties", {}), d.get("deviceProperties", {}), d.get("connectionProperties", {})
    return hw.get("udid", ""), props.get("name", ""), hw.get("platform", ""), conn.get("pairingState", ""), conn.get("tunnelState", "")

candidates = []
for d in devices:
    udid, name, platform, pairing, tunnel = describe(d)
    if wanted:
        if wanted in (udid, name, d.get("identifier")):
            candidates.append((udid, name))
    elif platform == "iOS" and pairing == "paired" and tunnel != "unavailable":
        candidates.append((udid, name))

if not candidates:
    for d in devices:
        print("  found: %s %s platform=%s pairing=%s tunnel=%s" % describe(d), file=sys.stderr)
    sys.exit("연결된 iPhone을 찾지 못했어요. 케이블로 연결하고 잠금을 푼 뒤 '이 컴퓨터를 신뢰'를 눌러 주세요.")
print(candidates[0][0], candidates[0][1])
PY
)
[[ -n "${UDID:-}" ]] || fail "기기를 고르지 못했어요."
echo "==> device: $NAME ($UDID)"

# 3. 서명 빌드. -allowProvisioningUpdates: 자동 서명이 프로비저닝 프로파일을 만들고 기기를 등록하게 한다.
echo "==> xcodebuild ($CONFIGURATION)"
build() {
    xcodebuild build \
        -project "$APP_DIR/WhenIOff.xcodeproj" \
        -scheme WhenIOff \
        -configuration "$CONFIGURATION" \
        -destination "id=$UDID" \
        -derivedDataPath "$DERIVED" \
        -allowProvisioningUpdates
}
if command -v xcbeautify >/dev/null; then build | xcbeautify; else build; fi

APP="$DERIVED/Build/Products/$CONFIGURATION-iphoneos/WhenIOff.app"
[[ -d "$APP" ]] || fail "빌드 결과가 없어요: $APP"
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"

# 4. 설치·실행
echo "==> install $BUNDLE_ID"
xcrun devicectl device install app --device "$UDID" "$APP"

echo "==> launch"
LAUNCH_ARGS=(device process launch --device "$UDID" --terminate-existing)
if [[ "${CONSOLE:-0}" == "1" ]]; then LAUNCH_ARGS+=(--console); fi
if ! xcrun devicectl "${LAUNCH_ARGS[@]}" "$BUNDLE_ID"; then
    cat >&2 <<'MSG'
실행하지 못했어요. 처음 설치했다면 iPhone에서:
  설정 → 일반 → VPN 및 기기 관리 → (개발자 앱) → 신뢰
그리고 설정 → 개인정보 보호 및 보안 → 개발자 모드가 켜져 있는지 확인한 뒤 다시 실행해 주세요.
MSG
    exit 1
fi
echo "==> done"

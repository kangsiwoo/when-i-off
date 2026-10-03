# ios

SwiftUI + CoreLocation 데이터 수집 앱이 들어갈 자리. 활성 경로의 집/정류장/목적지 geofence로 trip과
boarding_attempt를 자동 생성하고, GPS trace를 배치 업로드하며, 탔음/놓쳤음 버튼으로 결과를 기록한다.
상세는 [docs/DEVELOPMENT_PLAN.md](../docs/DEVELOPMENT_PLAN.md) Phase 2, 이슈 #4.

## 구성

| 디렉터리 | 내용 | 빌드 |
|---|---|---|
| `WhenIOffKit/` | 백엔드 API 클라이언트 (모델, 시각 코덱, HTTP) — #34 | **Linux·macOS 모두** `swift test` |
| `WhenIOffRecorder/` | 기록 코어: geofence 계획, 기록 상태기계, 오프라인 outbox, 출발 알림 계산 — #82 | **Linux·macOS 모두** `swift test` |
| `App/` | 앱 타깃(XcodeGen `project.yml`): CoreLocation·UserNotifications·Keychain·BGTask 어댑터, 파일 저장소, outbox 실행기, SwiftUI 화면 — #84 | **macOS + Xcode** (`ios-app-ci`, 시뮬레이터) |

판단 로직을 Apple 전용 프레임워크(SwiftUI, CoreLocation, UserNotifications, Security)에 의존하지 않는 Swift 패키지로
분리했다. 그래서 Xcode 없이도 CI(`ios-ci`, Linux)에서 빌드·테스트된다. 앱 타깃은 시스템 이벤트를 패키지에 넣고 나온
명령을 실행하는 얇은 어댑터다. 경계와 결정의 이유는 [ADR 0003](../docs/adr/0003-ios-app-architecture.md).

포맷 설정(`.swift-format`, 4칸·120자)은 `ios/`에 하나 두고 두 패키지와 앱이 같이 쓴다.

## WhenIOffKit

```bash
cd ios/WhenIOffKit
swift test                                   # 테스트
swift format lint --strict -r Sources Tests  # 포맷 (4칸, 120자 — .swift-format)
```

Swift 6.x 툴체인이면 된다. Linux는 [swift.org](https://www.swift.org/install/linux/)의 배포판을 쓴다.

```swift
let api = APIClient(configuration: APIConfiguration(baseURL: url, apiToken: token))
let routes = try await api.routes()
let trip = try await api.createTrip(
    CreateCommuteTripRequest(routeId: routes[0].id, tripDate: .today(), leftHomeAt: .now)
)
```

- **테스트 fixture는 실제 백엔드 응답이다** (`Tests/WhenIOffKitTests/Fixtures/`). 로컬 백엔드를 띄워
  동탄→수서(GTX-A) 경로로 기록 흐름을 한 바퀴 돌려 받았다. 백엔드 DTO가 바뀌면 여기서 깨진다
- **시각은 소수점 자릿수가 값마다 다르다** — 없음/3자리/6자리/9자리가 모두 온다. `Timestamp`가 전부 읽고,
  보낼 때는 밀리초 `Z`로 보낸다
- **`tripDate`는 KST 달력 날짜다** (`LocalDate`). UTC로 날짜를 뽑으면 출근길(KST 오전 7~9시 = UTC 전날
  22~24시)이 전부 하루 전으로 기록된다. `LocalDate.today()`의 기본값이 KST인 이유다
- 서버 주소와 토큰은 앱이 주입한다 (xcconfig, Keychain). 이 패키지는 보관하지 않는다

### 재시도: 다시 보내도 안전하게 만들기

`APIClient`는 **자동 재시도를 하지 않는다.** `APIError.transport`는 "서버에 도달했는지 모른다"는 뜻이고,
언제 다시 보낼지는 지하에서 누른 기록을 나중에 보내는 오프라인 outbox(`WhenIOffRecorder`의 `Outbox`)가 정한다.

다시 보내는 것 자체는 안전하다.
- `POST /commute-trips`: **`leftHomeAt`을 넣어 만들면** 서버가 `(경로, leftHomeAt)`으로 재전송을 흡수해
  기존 trip을 돌려준다(#37, 처음이면 201·재전송이면 200). `leftHomeAt` 없이 만든 trip은 다시 보내면
  중복 생성되므로, "집 나섬" 시각은 생성 요청에 담는다
- 탑승 시도 upsert(`(trip, routeLegId, attemptSeq)` 기준, 차 한 대 = 한 건 — 놓치고 다음 차를 타면
  `attemptSeq` 2)와 GPS 배치(`(user, recordedAt)` 중복 무시)도 서버가 중복을 흡수한다

## WhenIOffRecorder

```bash
cd ios/WhenIOffRecorder
swift test                                                 # 테스트 (../WhenIOffKit을 경로로 참조)
swift format lint --strict -r Sources Tests Package.swift  # 포맷
```

I/O가 없는 값 타입 넷이다. 앱은 시스템 이벤트를 넣고 결과를 실행한다.

| 타입 | 입력 → 출력 |
|---|---|
| `GeofencePlanner` | 활성 경로들 + 지금 시각 → 등록할 지역 ≤ 20개 (`GeofencePlan`). 가까운 지점(30m)은 합치고, 반경은 집·목적지 100m, 버스 60m, 지하철·GTX 120m |
| `Recorder` | `RecorderEvent`(지역 진입/이탈, 탔음/놓쳤음, 위치, tick) → `RecorderEffect`(outbox 명령, GPS 켜기/끄기, 탑승 알림, 확인 요청) |
| `Outbox` | 명령을 저장소(`OutboxStore` 프로토콜)에 쌓고 trip id를 풀어 하나씩 내준다. 결과에 따라 재시도(5초→15분 상한)·거부(400 → 기록 확인 목록)·정지(401) |
| `DepartureAlertPlanner` | 최신 추천 + 지금 → 로컬 알림 예약/취소 (출발 5분 전, 콜드스타트 문구) |

```swift
var recorder = Recorder(routes: routes, plan: planner.plan(routes: routes, now: .now))
for effect in recorder.handle(.regionExited(identifier, at: event.date)) {
    if case .enqueue(let command) = effect { try outbox.enqueue(command, now: .now) }
}
while let dispatch = try outbox.next(now: .now) {                  // 실행기(actor 하나)가 직렬로
    try outbox.complete(dispatch.entryId, await api.send(dispatch.request), now: .now)
}
```

- **모든 쓰기는 서버가 멱등으로 받는다.** trip은 `(경로, leftHomeAt)`(`TripKey`), 탑승 시도는
  `(trip, 구간, attemptSeq)` upsert, GPS는 `recordedAt` 중복 무시. 하차도 PATCH가 아니라 upsert로 보내서 앱이
  기억할 서버 id는 trip id 하나뿐이고, trip에 딸린 명령은 생성 응답이 올 때까지 줄을 선다
- **값을 지어내지 않는다.** 서버 규칙(trip 범위 #37, 시도 순서 #38)에 어긋나는 시각은 빼고 보내고
  `ReviewNote`로 사용자에게 고칠 거리를 알린다
- 상태(`RecorderState`, `OutboxState`)는 `Codable`이다. 앱은 바뀔 때마다 파일로 저장해, 백그라운드에서 깨어났다
  죽어도 이어서 기록한다

## 앱 (`App/`)

```
App/
  project.yml                 XcodeGen 정의 (.xcodeproj는 생성물, 커밋 안 함)
  Config/Base.xcconfig        공용 빌드 설정 → 있으면 Secrets.xcconfig를 include
  Config/Secrets.example.xcconfig   DEVELOPMENT_TEAM, WIO_BASE_URL, WIO_API_TOKEN 예시
  Support/Info.plist          권한 문구, UIBackgroundModes(location, fetch), BGTask 식별자
  WhenIOff/Core/              Foundation만 쓰는 접착 코드: RecordingSession(사건 → Recorder → 효과 실행),
                              OutboxDriver(전송 루프), JSONFile(원자적 저장), 시스템 경계 프로토콜
  WhenIOff/Platform/          CoreLocation·UserNotifications·Keychain·NWPathMonitor·BGTaskScheduler 구현
  WhenIOff/Features/          SwiftUI: 권한 안내, 기록 상태, 확인할 기록, 이력·trip 상세(시각 보정), 설정
  WhenIOffTests/              Swift Testing — 가짜 위치·알림·HTTP로 어댑터 흐름 확인
```

판단은 `WhenIOffRecorder`에 있고 앱은 번역만 한다. `Core/`의 `if`가 늘면 그 판단을 패키지로 옮긴다.

### 빌드·테스트 (Mac)

준비물: Xcode 16 이상, [XcodeGen](https://github.com/yonaskolb/XcodeGen) 2.42 이상(`brew install xcodegen`).

```bash
cd ios/App
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # 값 채우기 (커밋 금지, .gitignore)
xcodegen generate                                            # WhenIOff.xcodeproj 생성
open WhenIOff.xcodeproj
# 또는 시뮬레이터 테스트 (CI와 같음)
xcodebuild test -project WhenIOff.xcodeproj -scheme WhenIOff \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

`Secrets.xcconfig`가 없어도 시뮬레이터 빌드는 된다(주소·토큰 없음 → 기록을 쌓기만 하고 보내지 않는다).

| xcconfig 키 | 쓰임 |
|---|---|
| `DEVELOPMENT_TEAM` | 실기기 서명 팀 ID (Xcode → Settings → Accounts) |
| `WIO_BUNDLE_ID` | 기본 `com.kangsiwoo.whenioff`. 무료(개인) 팀에서 이미 쓰인 ID라고 하면 바꾼다 |
| `WIO_BASE_URL` | 백엔드 주소 → Info.plist `WIOBaseURL`. `//`가 주석이라 `http:/$()/192.168.0.10:8080`처럼 쓴다. LAN http는 ATS 예외(`NSAllowsLocalNetworking`), 그 밖은 https |
| `WIO_API_TOKEN` | **Debug 빌드에서만** 첫 실행에 Keychain으로 옮겨진다. 비워 두고 앱 설정 화면에서 넣어도 된다. Release 번들에는 실리지 않는다 |

토큰의 진실은 Keychain(`AfterFirstUnlockThisDeviceOnly`)이다 — 잠긴 기기에서 geofence로 깨어나도 읽힌다.
기록 상태와 outbox는 `Application Support/WhenIOff/*.json`(`completeUntilFirstUserAuthentication`)에 사건마다 저장된다.
형식이 맞지 않는 파일은 지우지 않고 `*.unreadable-<시각>.json`으로 옮겨 "확인할 기록"에 띄운다.

CI: `ios-app-ci`(macOS 러너)가 `xcodegen generate` → `xcodebuild build-for-testing` / `test-without-building`을
시뮬레이터에서 서명 없이 돌린다. `App/` 아래 Swift도 `swift format lint --strict`를 지킨다(`ios/.swift-format`).

### 실기기에서 실행

```bash
scripts/ios-device-run.sh                    # 생성 → 서명 빌드 → 설치 → 실행
CONSOLE=1 scripts/ios-device-run.sh          # 실행 뒤 앱 로그를 터미널에 붙인다
DEVICE="시우의 iPhone" scripts/ios-device-run.sh   # 여러 대가 연결돼 있을 때 (이름 또는 UDID)
```

처음 한 번 준비할 것:
1. **Xcode**(16 이상)와 **xcodegen**(`brew install xcodegen`), `sudo xcode-select -s /Applications/Xcode.app`
2. **Xcode → Settings → Accounts**에 Apple ID 로그인. 팀 ID(10자리)를 `Config/Secrets.xcconfig`의
   `DEVELOPMENT_TEAM`에 넣는다. 무료 개인 팀도 되지만 설치한 앱은 7일 뒤 만료되고 번들 ID가 유일해야 한다
3. iPhone을 케이블로 연결하고 잠금 해제 → **이 컴퓨터를 신뢰**
4. iPhone **설정 → 개인정보 보호 및 보안 → 개발자 모드** 켜기(재시동). 메뉴가 없으면 Xcode에 한 번 연결하면 생긴다
5. 첫 설치 뒤 실행이 막히면 iPhone **설정 → 일반 → VPN 및 기기 관리**에서 개발자 앱을 **신뢰**
6. 앱에서 위치 **항상 허용**, **정확한 위치** 켜기, 알림 허용. 설정 탭에서 토큰 확인

스크립트는 `xcodebuild -allowProvisioningUpdates`로 자동 서명(프로파일 생성·기기 등록)을 하고,
`xcrun devicectl device install app` / `device process launch`로 설치·실행한다.

### 아직 실기기에서 확인하지 않은 것 (#4 완료 기준)
- `CLLocationManager` 지역 감시의 백그라운드 전달·강제 종료 후 재실행, 지하역 진입 지연의 실제 크기
- 지역 사건으로 깨어난 백그라운드에서 `startUpdatingLocation`이 계속 도는지
- 알림 백그라운드 액션(탔음/놓쳤음)이 잠긴 기기에서 기록·전송까지 끝나는지
- Time Sensitive 알림 엔타이틀먼트(지금은 없음 → 집중 모드에서는 일반 알림)

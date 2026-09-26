# ios

SwiftUI + CoreLocation 데이터 수집 앱이 들어갈 자리. 활성 경로의 집/정류장/목적지 geofence로 trip과
boarding_attempt를 자동 생성하고, GPS trace를 배치 업로드하며, 탔음/놓쳤음 버튼으로 결과를 기록한다.
상세는 [docs/DEVELOPMENT_PLAN.md](../docs/DEVELOPMENT_PLAN.md) Phase 2, 이슈 #4.

## 구성

| 디렉터리 | 내용 | 빌드 |
|---|---|---|
| `WhenIOffKit/` | 백엔드 API 클라이언트 (모델, 시각 코덱, HTTP) — #34 | **Linux·macOS 모두** `swift test` |
| (앱 타깃) | SwiftUI 화면, 위치 권한, geofence — 아직 없음 | Xcode 필요 |

API 레이어를 Apple 전용 프레임워크(SwiftUI, CoreLocation, Security)에 의존하지 않는 Swift 패키지로 분리했다.
그래서 Xcode 없이도 CI(`ios-ci`, Linux)에서 빌드·테스트된다. 앱 타깃은 이 패키지를 가져다 쓰기만 한다.

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

### 재시도 주의: `POST /commute-trips`는 멱등이 아니다

`commute_trips`에 `(경로, 날짜)` 유일 제약이 없어서, 응답을 못 받은 채 다시 보내면 trip이 **중복 생성**된다.
그래서 `APIClient`는 **자동 재시도를 하지 않는다.** `APIError.transport`는 "서버에 도달했는지 모른다"는
뜻이므로, 재전송하려는 쪽은 먼저 `trips(routeId:from:to:)`로 같은 `leftHomeAt`의 trip이 이미 있는지
확인해야 한다. 지하에서 누른 기록을 나중에 보내는 오프라인 큐가 이 일을 맡는다(후속).

탑승 시도 upsert(`(trip, routeLegId)` 기준)와 GPS 배치(`(user, recordedAt)` 중복 무시)는 서버가
중복을 흡수하므로 그대로 다시 보내도 된다.

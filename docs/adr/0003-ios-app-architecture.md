# 0003. iOS 앱 구조 — 판단은 Linux에서 테스트되는 패키지에, 앱은 얇은 어댑터로

## 배경
Phase 2(#4)의 iOS 앱은 실측 기록(`commute_trips`, `boarding_attempts`, `gps_traces`)을 사람 손 없이 쌓는 것이
목표다. 이 기록이 캘리브레이션과 추천의 유일한 입력이라, 앱이 **틀린 값을 조용히 올리거나 기록을 잃는 것**이 가장
비싼 실패다. 착수 전에 정해야 할 제약은 다음과 같다.

- **iOS geofence는 앱당 20개**(`CLMonitor` 조건, `CLLocationManager` 지역 모두). 경로 둘(출근·퇴근)에 환승이
  있으면 지점이 금방 10개를 넘는다
- **geofence는 늦고 부정확하다.** 경계를 넘고 일정 거리·시간(수십 초~수 분)이 지나야 이벤트가 오고, 반경 100m
  아래는 보장되지 않는다. 지하 역사에서는 GPS가 끊겨 **진입이 아예 안 오거나 지상에 올라와서야 온다**
- **백그라운드 실행 제약.** 앱은 대부분 종료된 상태이고 geofence 이벤트·알림 액션·BGAppRefreshTask로 잠깐(수 초)
  깨어난다. 깨어났다가 언제든 다시 죽을 수 있으므로 상태는 매번 디스크에 있어야 한다. 기기가 잠겨 있을 때도 깨어난다
- **지하·이동 중에는 네트워크가 끊긴다.** 응답을 못 받은 요청이 서버에 반영됐는지 앱은 모른다
- 백엔드는 재전송을 이미 흡수한다(API.md).
  - trip 생성: `(routeId, leftHomeAt)`이 같으면 기존 trip을 `200`으로 (#37)
  - 탑승 시도: `(trip, routeLegId, attemptSeq)` upsert, 요청에서 `null`인 필드는 건드리지 않음 (#38)
  - GPS 배치: `(user, recordedAt)` 중복 무시
- 동시에 백엔드는 **틀린 기록을 400으로 거부한다.** 시도 시각은 trip의 `[leftHomeAt, arrivedDestinationAt]`
  안이어야 하고(#37), `attemptSeq`는 건너뛸 수 없으며, 뒤 시도의 출발이 앞 시도보다 이를 수 없고(#38, #42),
  `alightedAt ≥ vehicleActualDepartureAt`이다
- `WhenIOffKit`(#34)이 이미 "Apple 프레임워크 없는 패키지 → Linux CI에서 테스트"를 증명했다. 이 저장소에는 macOS
  러너가 아직 없다

## 결정

### 1. 모듈 경계

| 모듈 | 들어 있는 것 | 의존 | 테스트 |
|---|---|---|---|
| `ios/WhenIOffKit` (기존) | API DTO, `APIClient`, `Timestamp`, `LocalDate`(KST) | Foundation | Linux `swift test` |
| `ios/WhenIOffRecorder` (신규) | `GeofencePlanner`, `DirectionPolicy`, `Recorder`(상태기계), `Outbox`·`RetryPolicy`, `DepartureAlertPlanner`·`ColdStart`, `APIClient.send(_: OutboxRequest)` | Kit (경로 의존) | Linux `swift test` |
| 앱 타깃 `ios/App` (#84) | CoreLocation·UserNotifications·BackgroundTasks·Keychain 어댑터, 파일 저장소, outbox 실행기(메인 액터), SwiftUI 화면 | 두 패키지 | Xcode (macOS CI `ios-app-ci`) |

**판단은 전부 Recorder에, 앱은 번역만 한다.** 시스템 이벤트를 `RecorderEvent`로 바꿔 넣고, 나온
`RecorderEffect`(outbox 명령, GPS 켜기/끄기, 알림 띄우기)를 실행한다. 어댑터에 `if`가 생기면 그 판단을 Recorder로
옮길 신호다. #4의 모듈 구상(`Networking`/`Location`/`Storage`/`Features`)은 Networking = Kit,
Location·Storage의 판단 = Recorder, 그 시스템 쪽 절반과 `Features/*` = 앱 타깃으로 대응한다.

**Recorder는 Kit 안의 새 타깃이 아니라 별도 패키지다.**
- Kit은 "백엔드 계약" 하나만 책임진다. 백엔드 DTO가 바뀌면 Kit만, 기록 규칙이 바뀌면 Recorder만 바뀐다
- 의존 방향(Recorder → Kit)이 패키지 경계로 강제된다. Kit이 기록 로직을 알게 되는 일이 구조상 없다
- outbox 명령은 Kit의 요청 타입(`UpsertBoardingAttemptRequest` 등)을 **그대로** 담는다. 같은 DTO를 두 벌 두지 않는다
- 비용은 `Package.swift` 하나와 CI matrix 한 줄이다. 포맷 설정은 `ios/.swift-format` 하나를 같이 쓴다

### 2. 프로젝트 생성: XcodeGen
앱 타깃은 `ios/App/project.yml`(XcodeGen)로 정의하고 **`.xcodeproj`는 커밋하지 않는다**(생성물, gitignore).
XcodeGen 버전은 README에 고정하고, macOS CI 잡이 `xcodegen generate && xcodebuild build`(시뮬레이터)로 확인한다.

| 대안 | 판단 |
|---|---|
| `.xcodeproj` 커밋 | `project.pbxproj`는 사람이 리뷰할 수 없는 diff이고 파일 추가마다 머지 충돌이 난다. 1인 개발이라도 브랜치가 겹친다(CONVENTIONS의 PR 흐름) |
| Tuist | Swift 매니페스트·캐시·모듈 그래프 등 강력하지만, 앱 타깃 1개 + 로컬 패키지 2개에는 과하다. 자체 버전 관리 도구와 학습 비용이 붙는다. 타깃이 여럿(위젯, Live Activity 확장)으로 늘고 YAML이 버거워지면 다시 본다 |
| SwiftPM만으로 앱 | iOS 앱 번들(엔타이틀먼트, `UIBackgroundModes`, Info.plist)을 표준 Xcode 빌드로 만들 수 없다 |

XcodeGen은 YAML 한 장이 리뷰 가능한 진실이고, 로컬 패키지 참조(`packages: { WhenIOffRecorder: { path: ... } }`)를
그대로 지원한다.

### 3. geofence 계획 (`GeofencePlanner`)
- **대상**: 활성 경로들의 출발지, 도착지, TRANSIT 구간마다 승차·하차 정류장
- **반경** (`RadiusPolicy`, 설정에서 조정 가능): 집·목적지 100m, 버스 정류장 60m, 지하철·GTX 역 120m(지하 GPS 유실 대비)
- **합치기**: 30m 안의 지점은 한 지역으로 합치고 큰 반경을 쓴다. 지역 하나가 여러 역할을 가진다(집 = 출근 출발지 =
  퇴근 도착지, 환승역 = 앞 구간 하차 + 다음 구간 승차). 합치기는 한도 검사보다 먼저라 겹치는 역할은 한도에 걸리지 않는다
- **20개 한도와 우선순위** (결정적 — 같은 입력이면 같은 계획):
  1. 모든 경로의 **출발지** — trip 시작과 방향 판별의 유일한 근거
  2. **주 경로**(진행 중 trip의 경로, 없으면 지금 시각의 평소 방향 경로)의 도착지, 구간 순서대로 승차 → 하차
  3. 나머지 경로의 도착지와 정류장 (같은 단계 안에서는 경로 id 순)

  넘친 역할은 `dropped`로 돌려 화면에 "정류장 n곳은 자동 기록되지 않음"을 띄운다. 그 구간은 알림 대신 앱 화면의
  탔음/놓쳤음과 다음 지점 도착으로 추정 기록된다(§4)
- **식별자**는 역할에서 만든다(`wio.o.{route}`, `wio.b.{route}.{leg}` …). 다시 계획해도 같은 지점은 같은 식별자라
  어댑터는 등록된 지역과 식별자로 비교해 바뀐 것만 다시 건다
- **다시 계획하는 때**: 앱 실행·포그라운드(경로 재조회), trip 종료(`tripEnded`), 경로 변경, BGAppRefreshTask
- **출근/퇴근 판별** (`DirectionPolicy`): **이탈한 지역이 먼저, 시각은 그 다음.** 집을 나서면 집이 출발지인 경로,
  회사를 나서면 회사가 출발지인 경로다. 시각(KST 04–14시 = 출근이 평소)은 (a) 한 장소가 여러 경로의 출발지일 때
  고르는 기준, (b) 20개 한도의 주 경로, (c) 평소와 다른 방향의 시작을 확인받을지에만 쓴다. 평소와 다르면 기록은
  시작하되 "퇴근 기록을 시작했어요" 알림에 **취소** 액션을 단다 — 물리적 증거(어느 장소를 나섰나)가 시계보다 강하고,
  확인을 기다리다 `leftHomeAt`을 놓치는 것보다 시작 후 취소가 싸다

geofence 반경은 "도착 근사"다. trip 중에는 GPS가 켜져 있어 이벤트가 빨라지지만, 지하에서는 여전히 늦다.
그래서 자동 기록 시각은 사용자가 고칠 수 있어야 하고(trip 상세 화면, #4), 고칠 후보는 Recorder가 표시한다(§4).

### 4. 기록 상태기계 (`Recorder`)
I/O 없는 값 타입. `handle(_ event) -> [RecorderEffect]`이고 상태(`RecorderState`)는 `Codable`이라 앱이 매번 저장한다.
사건의 시각은 **사건이 일어난 시각**이다 — geofence는 `CLMonitor.Event.date`(`CLLocationManager` 어댑터는 전달된 시각 — 결과 참고), 버튼은 누른 시각.

| 사건 | 조건 | 명령 |
|---|---|---|
| 출발지 이탈 | trip 없음 | `createTrip(TripKey(경로, 이탈 시각), tripDate = KST 날짜)`, GPS 켜기 |
| 승차 정류장 진입 | 그 구간이 시작 전 | upsert `{leg, attemptSeq: 1, arrivedAtStopAt}`, 탔음/놓쳤음 알림 |
| 탔음 | 기다리는 시도 | upsert `{leg, seq, vehicleActualDepartureAt, CAUGHT}` |
| 놓쳤음 | 기다리는 시도 | upsert `{leg, seq, vehicleActualDepartureAt, MISSED, notes}`, **seq + 1**로 다시 알림 (#38) |
| 하차 정류장 진입 | 탄 구간 | upsert `{leg, seq, alightedAt}` — PATCH가 아니라 upsert라 attempt id가 필요 없다 |
| 도착지 진입 | | `updateTrip {arrivedDestinationAt}`, 남은 GPS 올림, GPS 끄기 |
| 위치 | trip 중 | 정확도 필터 후 버퍼, 묶음마다 `uploadGps` |
| tick | | GPS 묶음 시간 초과, 귀가·시간 초과 정리 |

뒤 시도(seq ≥ 2)에는 `arrivedAtStopAt`을 싣지 않는다 — 서버가 앞 시도의 출발 시각으로 스냅샷을 채운다(#42, #54).
`vehicleScheduledOrPredictedAt`도 보내지 않는다(서버가 채움).

**빠지거나 뒤바뀐 사건** — 원칙은 *값을 지어내지 않는다*. 확신할 수 없는 것은 빼고 보내고 `ReviewNote`로 사용자에게
고칠 거리를 알린다.
- 승차역 진입 없이 하차역 진입(지하 유실): 그 구간 seq 1을 `{alightedAt, CAUGHT}`로 만든다. 도착·출발 시각은 비운다
  (`boardStopMissed`)
- 탔음/놓쳤음 없이 다음 지점 도착: 기다리던 시도를 `CAUGHT`로 닫는다 — 다음 지점에 왔다는 것은 무언가를 탔다는 뜻이다.
  출발 시각은 비운다(`caughtInferred`). 탄 구간의 하차가 없으면 `alightMissed`, 아무 지점도 없던 구간은 `legSkipped`
- 집 이탈을 놓침(앱이 꺼져 있었음): 평소 방향 경로의 **첫 구간 승차역** 진입 시각을 `leftHomeAt`으로 trip을 시작한다.
  생성에는 멱등 키가 필요하고, 범위 규칙상 정류장 도착보다 늦을 수 없다(`leftHomeApproximated`, 사용자가 앞당겨 고친다)
- 출발 뒤 첫 정류장 전에 집에 다시 들어옴: 다시 나서면 `updateTrip {leftHomeAt}`으로 서버 값만 늦춘다(아직 시도가
  없어 범위 규칙에 걸릴 것이 없다). 로컬 `TripKey`는 처음 값 그대로 — 생성은 이미 성공해 outbox에서 빠졌으므로
  다시 보낼 일이 없다. 30분 넘게 머물면 기록을 접는다(`tripAbandoned`). 4시간 넘게 도착이 없어도 접는다
- 범위·순서 규칙을 어기는 값은 보내지 않는다: `leftHomeAt`보다 이른 사건, 이미 보낸 기록보다 이른 도착(도착을 빼고
  끝냄), 앞 시도보다 이른 출발, 출발보다 이른 하차(`timeOutOfOrder`)
- 도착 뒤에 늦게 온 사건: trip이 이미 끝났으므로 버린다. 같은 경로의 기록이 끝난 지 1시간 안의 승차역 진입은 새 trip으로
  보지 않는다
- 지역 경계 흔들림(같은 정류장 재진입): 처음 도착 시각을 덮어쓰지 않는다
- 지난 알림의 버튼(이미 끝난 시도, 다른 구간): 알림에 실은 `legId`/`attemptSeq`가 지금과 다르면 무시
- `attemptSeq` 20(서버 상한)에서 멈추고 알린다

### 5. 오프라인 outbox (`Outbox`)
**모든 쓰기는 서버의 자연 키로 보내는 멱등 요청이다.** 그래서 결과를 모르는 요청(타임아웃, 응답 유실, 앱 종료)은 그냥
다시 보낸다.

| 명령 | 서버 키 | 다시 보내면 |
|---|---|---|
| `createTrip` | `(routeId, leftHomeAt)` (#37) | 같은 trip이 `200` |
| `upsertAttempt` | `(trip, routeLegId, attemptSeq)` (#38) | 같은 행 갱신, `null` 필드는 그대로 |
| `updateTrip` | trip id | 같은 값을 다시 씀 |
| `uploadGps` | `(user, recordedAt)` | `ignored`로 흡수 |
| `deleteTrip` | trip id (#88) | 이미 지웠으면 `404` — 성공으로 본다 |

- **서버 id 매핑은 trip id 하나뿐이다.** 앱은 trip을 `TripKey(경로, leftHomeAt)`로 가리키고, trip에 딸린 명령은 그
  trip의 생성 명령 뒤에 줄을 선다. 생성 응답(201/200)의 id를 `tripIds[TripKey]`에 기억하면 줄이 풀린다. 생성 응답을
  잃어도 같은 생성 요청의 재전송이 같은 id를 돌려준다. 하차를 PATCH가 아니라 upsert로 보내므로 attempt id는 필요 없다
- **lane**: 같은 trip의 기록(생성 → 시도 → 도착)은 한 lane에서 **넣은 순서대로 한 번에 하나씩** 나간다. 앞 명령이
  재시도 대기면 뒤도 기다린다 — 서버 순서 규칙(번호 건너뛰기, 앞 시도보다 이른 출발, 범위 밖 기록) 때문이다. GPS는
  trip별 별도 lane이라 GPS 재시도가 탑승 기록을 막지 않는다. trip끼리는 서로 막지 않는다
- **합치기**: lane 맨 끝 항목이 아직 나가지 않았고 같은 시도(같은 구간·seq)의 upsert면 필드 단위로 합친다(`new ?? old`).
  서버가 `null`을 건드리지 않으므로 차례로 보낸 것과 결과가 같고, 지하에서 쌓인 요청 수가 준다. 보내는 중인 항목에는
  합치지 않는다. GPS는 서버 상한 500점으로 나눠 넣는다
- **재시도**(`RetryPolicy`, 순수 함수): 5초에서 두 배씩, 15분 상한, **포기하지 않는다**(실측 기록은 늦어도 올라가야 한다).
  1인 사용이라 지터는 없다. 네트워크가 돌아오면(`NWPathMonitor`) `expediteRetries()`로 대기를 풀어 바로 보낸다
- **실패 분류**

  | 결과 | 처리 |
  |---|---|
  | 전송 실패, 408·425·429·5xx | 재시도 |
  | 401·403 | outbox 전체 **정지**(지우지 않음). 설정에서 토큰을 고치면 `resume()` |
  | 400·404·409·422, 클라이언트 거부 | **버리고**(다시 보내도 같은 답) `DeadLetter`로 남긴다. 같은 lane의 다음 명령은 계속 간다. 단 `deleteTrip`의 404는 성공(#88) |
  | trip 생성이 거부됨 | 그 trip에 딸린 명령도 함께 `DeadLetter(.tripRejected)` — 보낼 곳이 없다 |
  | 2xx인데 본문을 못 읽음 | 서버에는 반영됐으니 성공으로 본다. 단 생성은 id가 필요해 거부로 본다(앱이 백엔드보다 낡음) |

  400은 Recorder가 규칙을 지켜 원래 나오지 않아야 한다(§4). 그래도 나오면(서버 규칙 변경, 사용자 수정과의 충돌)
  자동으로 고치지 않고 **기록 확인 화면**에 띄워 사용자가 trip 상세에서 고치게 한다
- **저장**: 상태 전체(`OutboxState`, `Codable`)를 `OutboxStore` 프로토콜로 변경마다 저장한다. 앱 구현은 Application
  Support의 JSON 파일(원자적 쓰기, `completeUntilFirstUserAuthentication` 보호 — 잠긴 기기에서 깨어나도 읽고 쓴다).
  보내던 중(`inFlight`)에 죽은 항목은 다시 시작할 때 재전송 대상으로 돌린다
- **실행기**: 앱의 actor 하나가 직렬로 `next → APIClient.send → complete`를 돈다. 깨우는 때: 명령이 들어올 때,
  포그라운드, 네트워크 복구, `nextWakeAt`, BGAppRefreshTask
- **취소**(#88, `Outbox.discard(trip:now:)`): 사용자가 trip을 취소하면 그 trip의 대기 명령을 버리고, 서버에 trip이 있을
  수 있으면 `deleteTrip`(`DELETE /commute-trips/{id}`)을 넣는다
  - 서버에 있을 수 있는 때: 생성 응답으로 id를 알거나, 생성 요청이 이미 한 번 나갔다(보내는 중, 또는 결과를 모른 채
    재시도 대기). 뒤의 경우 생성 명령은 버리지 않는다 — 다시 보내면 같은 trip의 id가 오고(#37) 그 id로 지운다.
    생성이 한 번도 나가지 않았으면 서버에 아무것도 없으니 버리기만 한다
  - 보내는 중인 명령은 되돌릴 수 없으므로 결과를 기다린다. 삭제는 record lane 맨 뒤에 서고, 같은 trip의 GPS lane에
    보내는 중인 것이 있어도 그것이 끝난 뒤에 나간다 — 먼저 지우면 늦게 닿은 기록이 `404`로 거부된다
  - 삭제의 `404`는 성공(이미 지웠다, 앞선 삭제가 응답만 잃었다). 생성이 거부되면 삭제는 거부 기록 없이 사라진다.
    성공하면 `tripIds`에서 그 키를 지운다
  - 저장된 outbox는 `OutboxCommand`의 합성 `Codable` 형식이라 사례 추가는 업데이트 전 파일을 그대로 읽는다(테스트로
    고정). 반대로 이 버전이 쓴 `deleteTrip`이 든 파일은 이전 앱이 읽지 못한다(앱 되돌리기는 하지 않는다)

### 6. GPS 수집
- trip 중에만 `CLLocationManager.startUpdatingLocation`(`allowsBackgroundLocationUpdates`, `UIBackgroundModes:
  location`, `pausesLocationUpdatesAutomatically = false`, `distanceFilter` 10m). 그 외에는 geofence만 — 배터리
- 수평 정확도가 없거나 100m 초과인 점은 버린다(#4). 음수 속도는 뺀다
- 30초 또는 100점마다 묶어 outbox에 넣는다(`uploadGps`). 업로드 자체는 outbox가 네트워크가 될 때 한다
- trip 종료(도착·귀가·시간 초과) 때 남은 점을 올리고 끈다

### 7. 입력: 알림 액션 (v1)
- 승차역 진입 시 알림 카테고리 `WIO_BOARDING`에 **탔음 / 놓쳤음** 액션(앱을 열지 않는 백그라운드 액션).
  `userInfo`에 `legId`, `attemptSeq`를 실어 지난 알림을 걸러낸다(§4). 알림 식별자는 구간 단위라 다음 시도가 앞
  알림을 교체한다. 인터럽션 레벨은 `timeSensitive`(집중 모드에서도 보이게)
- 출발 시각 기본값은 누른 시각, 앱 화면에서 고칠 수 있다(#4). "만석 통과" 같은 메모는 앱 화면에서
- Live Activity(잠금 화면 버튼, 다음 차 카운트다운)는 후속. 같은 `RecorderEvent`를 넣으면 되므로 상태기계는 바뀌지 않는다

### 8. 출발 알림 (#8, `DepartureAlertPlanner`)
- **조회**: 앱 실행·포그라운드, trip 종료 뒤, BGAppRefreshTask(`earliestBeginDate = nextRefreshAt`: 목표 도착
  120분 전부터 15분 간격, 출발 시각이 지나면 다음 앱 실행까지 없음). `GET /commute-routes/{id}/recommendation/latest`
- **예약**: `recommendedLeaveHomeAt − 5분`. 이미 그 창 안이면 바로, 출발 시각이 지났으면 알리지 않는다
- **문구**: 제목 "지금 나가세요 (성공 확률 P%)"(P는 버림 — 0.999를 100%로 말하지 않는다), 본문 "08:00 출발 권장 ·
  09:00 도착 목표"(KST). 콜드스타트면 "아직 데이터가 적어 보수적으로 추천했어요"를 붙인다
- **콜드스타트 판정**: 추천 응답에 샘플 수가 없다. `modelVersion == "v1"`(기본값만 쓰는 모델)이거나, 이력
  (`GET /commute-trips?routeId=`)에서 셌을 때 출발 시각이 기록된 시도가 5건 미만인 TRANSIT 구간이 있으면
  콜드스타트(analytics `MIN_CALIBRATION_SAMPLES = 5`와 같은 기준)
  → #86에서 응답에 `minTransitSampleCount`가 생겼다. 있으면 그 값이 5 미만일 때 콜드스타트이고, 이력 휴리스틱은
  값이 없을 때(V8 이전 추천, TRANSIT 구간 없는 경로)의 fallback으로만 남는다(결과 참고)
- **갱신**: 알림 식별자는 `(경로, 목표 날짜 KST)`. 추천이 바뀌면 같은 식별자로 다시 예약(교체)하되 30초 미만 차이와
  같은 문구면 그대로 둔다. 이미 울렸거나(`delivered`) 오늘 이미 나섰으면(trip 시작) 다시 울리지 않는다. 날짜가 바뀌면
  전날 것을 지우고 새로 예약. 추천이 없으면(404) 지운다
- APNs 푸시는 로컬 알림으로 부족할 때 별도 이슈(#8)

### 9. 설정·비밀
- **서버 주소**: `ios/App/Config/Secrets.xcconfig`(커밋 금지 — 기존 `.gitignore`의 `ios/**/*.xcconfig`)의
  `WIO_BASE_URL` → Info.plist `WIOBaseURL`. 저장소에는 `Secrets.example.xcconfig`만 둔다. xcconfig는 `//`를 주석으로
  읽으므로 `http:/$()/192.168.0.10:8080`으로 쓴다
- **API 토큰**: **Keychain이 진실**(`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — 잠긴 기기에서 geofence로
  깨어나도 읽혀야 한다). 설정 화면에서 입력·변경한다. Debug 빌드에 한해 xcconfig의 `WIO_API_TOKEN`이 있으면 첫 실행에
  Keychain으로 옮긴다(Release 번들에는 넣지 않는다). 패키지는 값을 보관하지 않고 주입만 받는다(`APIConfiguration`)

## 대안

- **판단 로직을 앱 타깃에 두고 XCTest로 테스트**: macOS 러너가 필요하고(아직 없음, 비용), CoreLocation을 흉내 내는
  계층이 테스트마다 커진다. 지금 구조에서는 시나리오 테스트가 사건 배열 하나다
- **outbox가 서버 id를 매핑(로컬 id ↔ 서버 id 표)**: 일반적인 방법이지만 attempt id까지 기억해야 하고, 매핑을 잃으면
  복구할 수 없다. 백엔드가 자연 키 멱등(#37, #38)을 이미 주므로 trip id 하나로 줄였다
- **하차를 `PATCH /boarding-attempts/{id}`로**: attempt id가 필요하다. upsert가 같은 일을 id 없이 한다
- **빠진 사건을 보간(예: 하차역 진입 시각 − 계획 이동시간 = 출발)**: 그럴듯한 가짜 값이 캘리브레이션에 섞인다. 비워 두고
  사람이 고치게 했다
- **방향을 시각만으로 판별**: 늦은 출근·이른 퇴근을 틀린다. 지역이 먼저다
- **활성 경로 하나만 geofence**(#4 초안): 출발지 둘(집·회사)은 늘 걸어야 방향을 판별할 수 있고, 합치면 둘이 지점 대부분을
  공유하므로 한도 안에서 두 경로를 모두 건다. 넘칠 때만 우선순위로 자른다
- **Core Data/SwiftData outbox**: 항목이 많아야 수백 개라 JSON 파일 하나면 충분하고 Linux에서 같은 코드로 테스트된다.
  커지면 `OutboxStore` 구현만 바꾼다
- **`CLMonitor` 대신 `CLLocationManager` 지역 감시**: 상태기계와 무관하다(어댑터 교체). 1순위는 `CLMonitor`
  (iOS 17, 이벤트에 `date`가 있고 조건이 재실행 뒤에도 유지된다). 실기기에서 백그라운드 전달이 불안정하면 교체한다.
  → #84는 `CLLocationManager`로 시작했다(결과 참고). 실기기에서 전달 시각이 실제 진입보다 많이 늦으면 `CLMonitor`로
  바꿔 `date`를 쓴다

## 결과
- `ios/WhenIOffRecorder` 패키지와 테스트가 이 결정을 구현한다(#82). `ios-ci`가 두 패키지를 matrix로 포맷·빌드
  (`-warnings-as-errors`)·테스트한다
- 앱 타깃(XcodeGen `ios/App/project.yml`, 어댑터, 화면, macOS CI `ios-app-ci`)은 #84에서 만들었다. 구현하며
  정한 것:
  - **지역 감시는 `CLLocationManager.startMonitoring(for: CLCircularRegion)`으로 시작했다**(§대안의 1순위
    `CLMonitor`가 아니다). 백그라운드 재실행 동작이 오래 알려진 API를 먼저 실기기에서 확인하기 위해서다. 대가로
    사건에 발생 시각이 없어 **전달된 시각**을 사건 시각으로 쓴다(§4의 `CLMonitor.Event.date` 대신). 진입이 늦게 오는
    만큼 시각도 늦게 잡히고, 그래서 trip 상세의 시각 보정이 필요하다. `CLMonitor`로 바꾸는 것은
    `Platform/LocationService.swift` 하나의 교체다
  - **outbox 실행기는 별도 actor가 아니라 메인 액터**(`OutboxDriver`)다. 사건 처리(`RecordingSession`)와 같은
    액터라서 상태기계가 낸 명령이 낸 순서대로 outbox에 들어간다 — 다른 actor로 넘기면 `await` 사이에 순서가 바뀔 수
    있고, 같은 trip의 기록은 순서가 서버 규칙이다. 네트워크 대기만 메인 밖에서 돈다
  - 등록된 지역과 계획의 비교(`GeofencePlan.sync(registered:)`), "그날 이미 나섰나"(`DepartureAlertPlanner.alreadyLeft`),
    지난 알림의 취소 버튼 거르기(`RecorderEvent.userCancelledTrip(TripKey?, at:)`)는 판단이라 Recorder에 두었다
  - `UIBackgroundModes`는 `location`, `fetch`(BGAppRefreshTask)만. `processing`은 쓰는 작업이 없다
  - 사건마다 순서: 상태기계 → 효과 실행(outbox 저장 포함) → 상태 저장. 둘 사이에 죽으면 상태기계가 한 걸음 뒤로
    가지만 서버로 갈 기록은 남는다
  - trip 상세의 시각 보정은 outbox를 거치지 않고 바로 PATCH한다(결과·400을 바로 보여 줘야 한다). 그 trip에 아직 보내지
    않은 명령이 있으면 보정을 막는다(뒤에 나간 upsert가 고친 값을 덮어쓸 수 있다)
  - Time Sensitive 알림 엔타이틀먼트는 아직 없다(무료 팀 서명 확인 전). 없으면 집중 모드에서 일반 알림으로 간다
  - 앱 타깃은 Swift 5 언어 모드(엄격 동시성 minimal)다. 델리게이트 격리를 Swift 6에 맞추는 것은 실기기 확인 뒤
- 실기기에서 확인할 것(#4 완료 기준): 지역 감시의 백그라운드 전달·재실행(강제 종료 후 포함), 전달 시각과 실제 진입의
  차이, 지하역 진입 지연의 실제 크기, 백그라운드에서 GPS 시작이 되는지, 잠긴 기기에서 알림 액션 → 전송까지
- 취소한 trip의 서버 기록은 처음에는 남았다(지우는 API가 없었다). #88에서 `DELETE /commute-trips/{id}`와 outbox의
  `deleteTrip`을 더해 지운다(§5 "취소")
- 저장된 outbox는 Kit 요청 타입의 JSON이다. Kit DTO를 바꾸는 앱 업데이트 뒤 읽기에 실패하면 파일을 옆으로 옮기고
  기록 확인 화면에 알린다(조용히 버리지 않는다)
- **콜드스타트 판정을 서버 값으로 옮겼다(#86).** analytics `recommend`가 고른 차량들의 예측 오차·차내 시간 입력이 기댄
  표본 수 중 최솟값을 `departure_recommendations.min_transit_sample_count`(V8)에 적재하고, 추천 응답이
  `minTransitSampleCount`로 싣는다. `ColdStart.isColdStart(_ advice:transitLegIds:samplesByLeg:)`는 v1이면 콜드스타트,
  아니면 서버 값이 있을 때 `< 5`, 없을 때만 기존 이력 휴리스틱을 쓴다.
  - **구간별 목록이 아니라 최솟값 하나**: 알림이 묻는 것은 "이 확률을 믿어도 되나" 하나이고, 확률은 구간 확률의
    곱이라 가장 얇은 입력이 정한다. 구간별 표시가 필요해지면 그때 응답을 넓힌다
  - **이력 휴리스틱보다 정확한 이유**: 서버 값은 모델이 실제로 쓴 입력의 표본이다. 시도가 5건 넘어도 보정 배치 전이거나
    다른 밴드라 기본값을 쓴 경우(0), 반대로 이력 창(90일) 밖이나 같은 노선·정류장의 다른 경로 기록을 합친 경우를 반영한다
  - **도보는 세지 않는다**: 도보 분포는 출발 시각만 당기고 성공확률에는 들어가지 않는다(ALGORITHM 3절)
  - `null`(키 없음)은 "모름"이지 0이 아니다 — V8 이전 행이 최신인 동안은 휴리스틱이 계속 쓰인다. analytics는 표본 수만
    바뀌어도 새 행을 덧붙이므로 다음 `recommend` 실행에서 값이 생긴다
- 후속 후보: trip 중 GPS로 지점 진입을 직접 판정해 시각을 보정(소프트웨어 geofence), Live Activity

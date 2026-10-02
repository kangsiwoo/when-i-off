# API 설계 (Backend, Kotlin/Spring)

앱(Swift)과 데스크탑(TS/React)이 공용으로 쓰는 REST API. 구현된 엔드포인트의 실제 스펙은
서버의 Swagger UI(`/swagger-ui.html`, OpenAPI JSON `/v3/api-docs`)가 진실이고, 이 문서는
계약의 의도와 아직 구현 전인 부분을 적는다. 그 스펙의 스냅샷이 `desktop/openapi.json`이며 desktop의 API 타입은
여기서 생성된다(`./gradlew exportOpenApi`, [desktop/README.md](../desktop/README.md#api-타입-재생성)). 표의 **상태** 열: `✔` #10에서 구현, `계약` 초안만.

## 공통 규약

- 프리픽스 `/api/v1`. 시각은 ISO-8601 UTC(`2026-09-10T09:58:19Z`), 날짜는 `yyyy-MM-dd`(KST 기준 trip 날짜).
- **인증**: 1인 사용 단계라 고정 토큰. 모든 `/api/**` 요청에 `X-Api-Token: <WIO_API_TOKEN>` 헤더.
  없거나 다르면 `401` (아래 에러 포맷). `/actuator/health`, `/swagger-ui.html`, `/v3/api-docs`는 인증 없음.
- 요청/응답 모두 DTO. 엔티티가 그대로 나가지 않으며 `null` 필드는 응답에서 생략된다.
- 좌표는 WGS84 `lat`/`lng`(double).

### 에러 포맷 (RFC 7807 Problem Details)

모든 에러는 `Content-Type: application/problem+json`으로 Spring `ProblemDetail` 형식이다.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "validation failed",
  "instance": "/api/v1/gps-traces/batch",
  "errors": ["points[3].lat: must be less than or equal to 90.0"]
}
```

| 상태 | 언제 | 비고 |
|---|---|---|
| 400 | 검증 실패(`@Valid`), 잘못된 파라미터, 도메인 규칙 위반(예: `alightedAt`가 출발보다 앞) | 검증 실패는 `errors[]`에 필드별 메시지 |
| 401 | `X-Api-Token` 없음/불일치 | |
| 404 | 리소스 없음 또는 **다른 사용자 소유** (존재 여부를 숨김) | |
| 409 | UNIQUE/FK 위반 등 무결성 오류, 실측 기록이 붙은 구간을 지우는 구간 교체, 같은 `leftHomeAt`인데 `tripDate`가 다른 trip 생성 | `detail`에 DB 메시지 첫 줄 또는 문제의 구간 id |
| 502 | 외부 API(KLID/TAGO)가 오류 코드/비JSON 응답 | KLID는 `klidResultCode`(`K22` 같은 결과 코드, 비JSON이면 `HTTP403` 형식), TAGO는 `tagoResultCode`(`resultCode`가 `"00"`이 아닌 경우) 확장 필드 |
| 503 | 서비스 키 미설정 (`TAGO_BUS_API` 또는 KLID `REALTIME_TREFFIC_LIGHT_API`) | 관리 동기화 API에서만 |
| 500 | 그 외 | `detail`은 항상 `"unexpected error"`, 원인은 서버 로그 |

## 경로/구간 관리 (데스크탑에서 주로 사용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/commute-routes` | 내 출퇴근 경로 목록 |
| ✔ | POST | `/commute-routes` | 경로 생성 (이름, 방향, 출발/도착 좌표, 기본 목표 도착 시각·대상 day_type) → `201` |
| ✔ | GET | `/commute-routes/{id}` | 경로 상세 (구간 + 구간별 신호등 crossing, TRANSIT 구간의 노선·정류장 — 아래 "경로 상세의 TRANSIT 구간") |
| ✔ | PATCH | `/commute-routes/{id}` | 이름·사용 여부·기본 목표 도착 시각·대상 day_type 일부 수정 (아래 "기본 목표 도착 시각") |
| ✔ | PUT | `/commute-routes/{id}/legs` | 구간 목록 교체 (순서 재정렬 포함, 아래 "구간 교체의 의미"). WALK/TRANSIT 필드 규칙은 DB CHECK와 동일, 좌표는 WGS84 범위 검증 |
| ✔ | PUT | `/route-legs/{id}/signal-crossings` | WALK 구간의 교차로 순서 전체 교체. 항목: `trafficSignalId`, `approachDir`(`nt…nw`), `signalKind`(`Bs,Bc,Lt,Pd,St,Ut`, 기본 `Pd`) |
| ✔ | POST | `/transit-lines` | 노선 수동 등록 (`mode`, `name`, `stdgCd?`, `externalId?`, `hasRealtimeApi`) |
| ✔ | GET | `/transit-lines/search?mode=BUS&keyword=` | 노선 검색 (구간 등록 시 자동완성용, `mode` 생략 가능) |
| ✔ | POST | `/transit-stops` | 정류장/역 수동 등록 |
| ✔ | GET | `/transit-stops/nearby?lat=&lng=&radiusM=&mode=` | 근처 정류장/역 (`radiusM` 기본값은 서버 설정, `mode` 생략 가능) |
| ✔ | GET | `/transit-lines/{id}/schedules/next?stopId=&direction=&at=&limit=` | 정적 시간표 기준 다음 출발 N대 (아래 "정적 시간표") |
| ✔ | POST | `/traffic-signals` | 교차로 수동 등록 (좌표 + 이름) |
| ✔ | GET | `/traffic-signals/nearby?lat=&lng=&radiusM=` | 근처 교차로 |

### 기본 목표 도착 시각 (#68)
경로는 일괄 추천(analytics `recommend --all-active-routes`, cron 한 줄)이 쓸 기본 목표를 갖는다.

- `defaultTargetArrivalTime`: KST 벽시계 `HH:mm[:ss]`, 응답은 `HH:mm:ss`. 없으면 응답에서 **키째로 빠지고**
  그 경로는 일괄 추천에서 빠진다
- `defaultTargetDayTypes`: `WEEKDAY` / `SATURDAY` / `SUNDAY_HOLIDAY`의 집합. 그날의 day_type(공휴일은
  `SUNDAY_HOLIDAY`)이 들어 있을 때만 돈다. 생성 시 생략하면 `["WEEKDAY"]`, 빈 배열은 400. 응답은 enum 순서로 정렬
- 목록·생성·상세(`route`) 응답 모두 두 필드를 준다

`PATCH /commute-routes/{id}` 본문은 바꿀 필드만 보낸다 (`null`/생략 = 그대로, trip PATCH와 같은 규칙):

```json
{ "name": "평일 출근", "isActive": true, "defaultTargetArrivalTime": "09:00", "defaultTargetDayTypes": ["WEEKDAY"] }
```

- 목표 시각은 `null`이 "그대로"라서 지우려면 `"clearDefaultTargetArrivalTime": true`를 보낸다. 시각과 같이 보내면 400
- `name`이 공백뿐이면 400, 앞뒤 공백은 잘라 저장한다. 내 경로가 아니거나 없으면 404
- 응답은 `GET /commute-routes` 목록 항목과 같은 모양

### 경로 상세의 TRANSIT 구간
TRANSIT 구간은 `transitLineId`/`boardStopId`/`alightStopId`와 함께 **노선과 승하차 정류장 객체**를 준다 (#36).
앱이 "동탄 → 수서 (GTX-A)"를 띄우고 승하차 정류장 geofence를 거는 데 필요한 것이 경로 상세 한 번으로 온다.
객체는 `POST /transit-lines`, `/transit-stops/nearby`의 응답과 같은 모양이다.

```json
{ "id": 2, "seqOrder": 2, "legType": "TRANSIT",
  "transitLineId": 1, "boardStopId": 4, "alightStopId": 1, "plannedTravelSec": 1260,
  "transitLine": { "id": 1, "mode": "GTX", "name": "GTX-A (수서~동탄)", "stdgCd": "GTX-A", "externalId": "L09", "hasRealtimeApi": false, … },
  "boardStop":  { "id": 4, "mode": "GTX", "name": "동탄", "lat": 37.201167, "lng": 127.095111, … },
  "alightStop": { "id": 1, "mode": "GTX", "name": "수서", "lat": 37.48694, "lng": 127.10194, … },
  "signalCrossings": [] }
```

- `…Id` 필드는 하위 호환으로 남긴다
- WALK 구간은 세 객체가 null이라 **키째로 빠진다** (`non_null`)
- 노선·정류장 단건 조회(`GET /transit-stops/{id}` 등)는 두지 않았다. 앱이 경로를 그리려고 구간마다 2~3번씩 더
  부르게 되기 때문이다. 경로 상세는 노선·정류장을 한 쿼리로 함께 읽는다 — TRANSIT 구간이 늘어도 쿼리 수가 늘지 않는다

### 구간 교체의 의미
`PUT /commute-routes/{id}/legs`는 목록을 통째로 보내지만, 기존 구간을 지우고 다시 만드는 것이 아니라
**제자리에서 맞춰 고친다**. 실측 기록(`boarding_attempts`, `walking_segments`, `user_walking_profile`)과
신호등 crossing은 `route_leg_id`로 구간에 붙어 있으므로 구간 id가 유지되어야 한다.

- 항목에 `id`가 있으면 그 구간(이 경로의 것이어야 함, 아니면 400)을 수정한다. 재정렬은 `id`와 새 `seqOrder`로 표현한다
- `id`가 없으면 `seqOrder`와 `legType`이 같은 기존 구간이 있을 때 그것을 수정하고, 없으면 새로 만든다
- 요청에 없는 기존 구간은 삭제한다. 다만 실측 기록이 있는 구간을 삭제하거나 종류(WALK↔TRANSIT)를 바꾸려 하면
  `409` — 기록은 지우지 않는 게 원칙이므로 그 구간은 `id`를 붙여 되돌려 보내야 한다
- 응답은 `GET /commute-routes/{id}`와 같은 상세이며, 유지된 구간은 같은 `id`와 crossing을 그대로 가진다

수동 등록은 TAGO/KLID 마스터 동기화가 커버하지 않는 곳(GTX 역, TAGO에 없는 노선, 지방 교차로)을
위한 것이다. 동기화로 들어온 행은 `stdgCd`+`externalId`(교차로는 `crsrdId`)가 채워져 있고,
수동 행은 NULL이다.

## 이동 기록 (앱에서 주로 사용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | POST | `/commute-trips` | 이동 시작 (`routeId`, `tripDate`, `leftHomeAt?`) → `201`, 재전송이면 `200` (아래) |
| ✔ | PATCH | `/commute-trips/{id}` | `leftHomeAt`, `arrivedDestinationAt` 갱신. 이미 기록된 attempt가 새 범위 밖이 되면 400 |
| ✔ | GET | `/commute-trips?routeId=&from=&to=` | 이력 조회 (attempts 포함, 히스토리 화면용). `to < from`이면 400 |
| ✔ | POST | `/commute-trips/{id}/boarding-attempts` | TRANSIT 구간 탑승 시도(차 한 대) **upsert** (아래) |
| ✔ | PATCH | `/boarding-attempts/{id}` | 결과 갱신 (`vehicleActualDepartureAt`, `alightedAt`, `result`, `notes`). id로 한 건만. 예측 스냅샷 채움은 upsert와 같다 |
| ✔ | POST | `/gps-traces/batch` | GPS 포인트 배치 업로드 (아래) |
| ✔ | GET | `/commute-trips/{id}/gps-traces` | 그 trip에 묶인 GPS 포인트, 기록 시각 순 (데스크탑 trip 상세의 트랙 오버레이, 아래) |

### trip 생성의 재전송 (#37)
지하에서 "집 나섬" 응답을 못 받은 앱은 같은 요청을 다시 보낸다. 같은 경로에서 **밀리초까지 같은
`leftHomeAt`**의 서로 다른 출근은 없으므로 `(routeId, leftHomeAt)`를 키로 재전송을 흡수한다
(부분 유일 인덱스 `uq_commute_trips_route_left_home`).

- 처음이면 생성 → `201 Created`. 같은 키의 trip이 이미 있으면 **그 trip을 `200 OK`로** 돌려준다.
  본문은 그 사이 기록된 attempts까지 포함한 trip 전체. 동시에 둘 들어와도 진 쪽은 `200`을 받는다
- 같은 키인데 `tripDate`가 다르면 `409` — 재전송이라면 본문이 같아야 한다. 날짜를 다르게 계산한
  클라이언트 버그(KST 대신 UTC)를 조용히 가리지 않기 위해서다
- `leftHomeAt` 없이 만든 trip은 키가 없으므로 매번 새로 만든다. 재전송해도 안전하려면 `leftHomeAt`을
  생성 요청에 넣는다

### 기록 시각은 trip 안에 있어야 한다 (#37)
attempt의 `arrivedAtStopAt`, `vehicleActualDepartureAt`, `alightedAt`은 trip의
`[leftHomeAt, arrivedDestinationAt]` 안이어야 한다(값이 있는 쪽만 검사). 벗어나면 400 —
집을 나서기 전에 역에 도착했거나 회사에 도착한 뒤 하차한 기록은 GPS 지연·수동 입력 실수다.
trip `PATCH`로 범위를 줄여 이미 기록된 attempt가 밖으로 밀려나도 400이다.
`vehicleScheduledOrPredictedAt`은 검사하지 않는다 — 예측 시각은 집을 나서기 전 값일 수 있다.

### boarding attempt의 upsert 의미
attempt는 **차 한 대 = 한 건**이다 (#38). 한 구간에서 차를 놓치고 다음 차를 타면 그 구간의 attempt는
둘이고, `attemptSeq`(1부터)로 몇 번째 차인지 구분한다(`UNIQUE (commute_trip_id, route_leg_id, attempt_seq)`).
앱은 geofence 이벤트마다, 그리고 오프라인 후 재전송 때 같은 요청을 여러 번 보낼 수 있으므로
`POST /commute-trips/{id}/boarding-attempts`는 **`(routeLegId, attemptSeq)`를 키로 upsert**한다.

- `attemptSeq`는 생략하면 `1`. 한 대만 시도한 구간은 예전처럼 보내면 된다
- 없으면 생성 → `201 Created`, 있으면 갱신 → `200 OK`. 본문은 둘 다 attempt 전체(`attemptSeq` 포함).
  같은 (trip, leg, attemptSeq)의 첫 요청이 동시에 둘 들어와도 진 쪽은 갱신으로 다시 처리되어 `200`을
  받는다 (409가 아님)
- **번호를 건너뛸 수 없다.** 새 `attemptSeq`는 그 구간의 현재 최댓값 + 1 이하여야 한다(첫 시도는 1).
  아니면 400 — 중간 시도가 빠진 기록은 "그 사이 놓친 차"를 잃는다. 범위는 1~20
- 같은 구간에서 뒤 시도의 `vehicleActualDepartureAt`이 앞 시도의 `vehicleActualDepartureAt`보다
  이르면 400 (값이 있는 쪽만). 뒤 시도의 `arrivedAtStopAt`은 이 검사를 받지 않는다(#42) — 처음 도착한
  시각을 그대로 실어도 되고 생략해도 된다. 도보 구간은 첫 시도의 도착만 쓴다
- trip의 `boardingAttempts`는 구간 순서(`seqOrder`) → `attemptSeq` 순으로 나온다

예: 역 도착 → 22:43 차 놓침 → 22:58 차 탐

```
POST …/boarding-attempts {routeLegId: 2, attemptSeq: 1, arrivedAtStopAt: 22:43:30,
                          vehicleActualDepartureAt: 22:43, result: MISSED}          → 201
POST …/boarding-attempts {routeLegId: 2, attemptSeq: 2,
                          vehicleActualDepartureAt: 22:58, result: CAUGHT}          → 201
```

두 건이 따로 남는다. 각 요청을 다시 보내면 자기 행만 갱신한다(`200`). 하차는 탄 시도에
`{routeLegId: 2, attemptSeq: 2, alightedAt: …}`로 보낸다.

- **요청에서 `null`(생략)인 필드는 건드리지 않는다.** 정류장 도착만 보낸 뒤 나중에 하차만 보내도
  앞의 값이 지워지지 않는다. 값을 지우는 API는 없다 (실측 기록은 지우지 않는 게 원칙)
- `routeLegId`는 그 trip의 경로에 속한 `TRANSIT` 구간이어야 한다. 아니면 400
- `alightedAt < vehicleActualDepartureAt`이면 400
- `vehicleScheduledOrPredictedAt`는 그 순간 시스템이 알던 "다음 차" 시각의 **스냅샷**이다.
  앱은 보통 보내지 않는다 — 비어 있으면 서버가 채운다(아래)

### 예측 스냅샷은 서버가 채운다 (#54)
upsert(`POST …/boarding-attempts`)와 `PATCH /boarding-attempts/{id}` 모두, 처리 뒤 attempt의
`vehicleScheduledOrPredictedAt`이 비어 있고 기준 시각이 있으면 서버가 채운다. 응답 본문에 채운 값이 실린다.

- **기준 시각**: 첫 시도(`attemptSeq = 1`)는 `arrivedAtStopAt`, 뒤 시도(n > 1)는 앞 시도(n − 1)의
  `vehicleActualDepartureAt`(앞 차가 떠난 순간의 다음 차). 없으면 비워 두고, 그 값이 들어오는 다음
  upsert/PATCH에서 채운다. 앞 시도에 출발 시각이 나중에 들어오면 그 요청에서 바로 다음 시도도 채운다
- **고르는 순서**
  1. 실시간: 그 구간의 (노선, 승차 정류장) `transit_arrival_observations` 중 `observed_at`이
     `[기준 − 2분, 기준]`(양 끝 포함)인 **가장 최근 관측 묶음**(같은 `observed_at` — 폴러가 한 번 조회한
     차량들)에서 기준 시각 이후(같은 시각 포함) 가장 이른 `predicted_arrival_at`. 기준 시각 뒤의 관측은
     그때 몰랐던 값이라 쓰지 않고, 허용 오차는 두지 않는다. 최근 묶음에 기준 이후 차가 없으면 더 오래된
     묶음으로 내려가지 않고 2로 간다
  2. 정적 시간표: 구간 방향(승차·하차 정류장으로 판정)으로 기준 시각 이후(같은 시각 포함) 첫 출발.
     day_type·자정 경계는 `GET /transit-lines/{id}/schedules/next`와 같다
  3. 둘 다 없으면 `null`
- **서버는 값이 있으면 바꾸지 않는다.** 한 번 채운 값은 기준 시각을 고치거나 새 관측이 쌓여도 그대로다
  (그 순간의 스냅샷). 앱이 값을 보내면 다른 필드처럼 그 값이 저장된다 — 앱이 보낸 값이 우선이다
- 관측은 기준 시각 이전 것만 보고 시간표는 고정이라, 늦게 채워도 그 순간 계산했을 때와 같은 값이다
- 값이 어느 출처(관측/시간표)에서 왔는지는 저장하지 않는다

### GPS 배치 업로드
- 요청: `{ "tripId": 123 | null, "points": [ {recordedAt, lat, lng, speedMps?, accuracyM?}, … ] }`
- **한 요청에 1~500포인트.** 비어 있거나 500 초과면 400. 앱은 10~30초 또는 위치 변화 임계치마다
  모아서 보낸다 (배터리/네트워크 절약)
- 검증: `lat ∈ [-90, 90]`, `lng ∈ [-180, 180]`, `speedMps`/`accuracyM ≥ 0`
- **중복은 무시**: `(user_id, recorded_at)` UNIQUE + `ON CONFLICT DO NOTHING`. 응답
  `{ "accepted": n, "ignored": m }`으로 실제 삽입 수와 중복 수를 돌려주므로 앱은 재전송을
  마음 놓고 할 수 있다 (idempotent)
- `tripId`가 있으면 내 trip이어야 한다 (아니면 404). 없으면 상시 수집분으로 저장

### trip의 GPS 트랙 (#64)
`GET /commute-trips/{id}/gps-traces` → `[{ "recordedAt", "lat", "lng", "accuracyM"?, "speedMps"? }, …]`

- 내 trip이 아니거나 없으면 `404` (다른 조회와 같다). 포인트가 없으면 빈 배열(`200`)
- `commute_trip_id`가 그 trip인 행만 준다. 상시 수집분(`tripId` 없이 올린 포인트)은 시각이 겹쳐도 넣지 않는다
- `recorded_at` 오름차순(동점은 id). `accuracyM`/`speedMps`는 앱이 안 보냈으면 키째로 빠진다
- 페이지네이션은 없다. 한 번 출퇴근(1~2시간, 10~30초 간격)이면 수백 포인트라 한 번에 준다

## 추천 조회

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/commute-routes/{id}/recommendation?targetArrivalAt=` | 목표 도착 시각 기준 추천 출발 시각 조회 |
| ✔ | GET | `/commute-routes/{id}/recommendation/latest` | 목표 시각과 무관하게 가장 최근 계산된 추천 |
| ✔ | GET | `/commute-routes/{id}/recommendation-history?from=&to=` | 날짜별 추천(버전별 마지막 계산)과 그날 trip 결과 (추천 vs 실제, #62) |

`recommendation` 엔드포인트는 Backend가 직접 계산하지 않고, Analytics가 미리 계산해
`departure_recommendations`에 적재해 둔 값을 읽거나(캐시), 캐시가 없으면 즉석 계산을
Analytics 내부 API(`analytics-service:/internal/recommend`)에 위임하는 두 방식을 열어둔다.
초기 구현은 "배치로 미리 계산 + 캐시 조회"만으로 충분하다.

적재하는 쪽은 #22에서 구현됐다 — `uv run wio-analytics recommend --route-id … --target-arrival-at …`
(콜드스타트 기본값, `model_version=v1`, [analytics/README.md](../analytics/README.md)). 조회 두 개는
#24에서 붙었고(캐시 조회만, 즉석 계산 위임은 아직 없다), 이력은 #62에서 `recommendation-history`로 붙었다
(아래 "추천 이력").

### 조회 두 개의 계약

```json
{
  "recommendedLeaveHomeAt": "2026-09-20T22:24:00Z",
  "targetArrivalAt": "2026-09-21T00:00:00Z",
  "catchProbability": 0.91,
  "bufferSeconds": 660,
  "modelVersion": "v1",
  "computedAt": "2026-09-20T21:00:00Z"
}
```

- `computedAt`은 analytics가 그 추천을 계산한 시각이다 (`departure_recommendations.computed_at`)
- `targetArrivalAt`은 **필수**이고 ISO-8601 절대 시각이다. 없거나 파싱 실패면 `400`
- `/latest`는 목표 시각을 가리지 않고 그 경로에서 가장 늦게 계산된 한 건을 준다
- 추천이 한 건도 없으면 `404`다 (빈 `200`이 아니라). 경로가 없거나 내 것이 아닐 때도 같은 `404`
- `departure_recommendations`는 과거 추천을 지우지 않고 누적하므로
  ([DATA_MODEL.md](./DATA_MODEL.md)) 같은 (경로, 목표 시각)에 행이 여러 개다. 두 조회 모두
  `computed_at DESC, id DESC`로 **최신 한 건**만 고른다 — `computed_at` 기본값이 트랜잭션
  시각이라 한 번에 들어간 행끼리는 값이 같을 수 있어 id로 동점을 깬다

### 추천 이력 (추천 vs 실제, #62)

`GET /commute-routes/{id}/recommendation-history?from=2026-09-01&to=2026-09-30`

```json
[
  {
    "date": "2026-09-21",
    "recommendations": [
      {
        "recommendedLeaveHomeAt": "2026-09-20T22:24:00Z",
        "targetArrivalAt": "2026-09-21T00:00:00Z",
        "catchProbability": 0.91,
        "bufferSeconds": 660,
        "modelVersion": "v1",
        "computedAt": "2026-09-20T21:00:00Z"
      }
    ],
    "trips": [
      {
        "tripId": 31,
        "leftHomeAt": "2026-09-20T22:27:10Z",
        "arrivedDestinationAt": "2026-09-20T23:58:40Z",
        "allLegsCaught": true,
        "missedCount": 1
      }
    ]
  }
]
```

- `from`·`to`는 **필수** KST 날짜(`yyyy-MM-dd`, 양끝 포함). 없거나 형식이 틀리거나 `to < from`이면 `400`.
  경로가 없거나 내 것이 아니면 `404`
- 날짜는 KST다. 추천은 `target_date`(analytics가 `target_arrival_at`의 KST 날짜로 채운다), trip은 `trip_date`로 묶는다.
  추천이나 trip 중 **하나라도 있는 날만** 날짜 오름차순으로 준다. 아무 것도 없으면 빈 배열(`200`)
- `recommendations`: `modelVersion`마다 그날 **마지막으로 계산된** 한 건, `modelVersion` 순. 고르는 기준은 위 조회와
  같은 `computed_at DESC, id DESC`다. analytics는 `(경로, target_date, target_arrival_at, model_version)`당 한 행을
  제자리에서 갱신하므로(`computed_at`도 바뀐다) 보통 버전당 목표 시각별 한 행이지만, 그날 목표 시각이 여러 개면
  그 중 마지막 계산 하나만 고르고 어느 목표 시각의 것인지는 `targetArrivalAt`으로 알린다
- `trips`: 그날 이 경로의 trip 전부, `leftHomeAt` 순(없으면 뒤). `allLegsCaught`는 경로의 TRANSIT 구간마다
  `CAUGHT` 시도가 있는가(TRANSIT 구간이 없는 경로면 `true`), `missedCount`는 `MISSED` 시도 수(놓친 차 대수)
- 추천 대비 출발 차이, 목표 대비 도착 차이(지각) 같은 파생값은 화면이 계산한다

## 외부 데이터 동기화 (TAGO/KLID) — 관리 API

동기화 자체는 Backend 내부 스케줄러가 하지만, 마스터 동기화와 폴링 1회 실행을 손으로 시킬 수
있게 **관리 엔드포인트**를 둔다. 같은 `X-Api-Token`으로 보호되며 앱/데스크탑 일반 화면에서는
호출하지 않는다. 버스는 TAGO, 신호등은 KLID를 쓴다 — 배경은
[ADR 0001](./adr/0001-tago-bus-arrival-prediction.md), 호출 한도 정책은
[ARCHITECTURE.md](./ARCHITECTURE.md) "외부 데이터 동기화".

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | POST | `/admin/sync/tago/bus-route?cityCode=&routeNo=` | `getRouteNoList`로 `routeId` 검색 + `getRouteAcctoThrghSttnList` → `transit_lines`, `transit_stops`, `transit_line_stops` upsert (노선 하나 단위) |
| ✔ | POST | `/admin/sync/klid/intersections?stdgCd=` | `crsrd_map_info` → `traffic_signals` upsert |
| 계약 | POST | `/admin/sync/tago/bus-arrivals` | 활성 경로의 TRANSIT(BUS) 구간마다 `getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList` 1회 수집 → `transit_arrival_observations` (`source='TAGO_ARVL'`) |
| 계약 | POST | `/admin/sync/klid/signal-states?stdgCd=` | `tl_drct_info` 1회 수집 → `traffic_signal_states` |
| ✔ | POST | `/admin/schedules/import` | 정적 시간표 CSV 업로드 (multipart, 파트명 `file`) → `transit_schedules` 교체 (아래 "정적 시간표") |
| 계약 | GET | `/admin/sync/status` | 잡별 마지막 실행 시각/결과, 폴링 활성 여부와 현재 대상 범위 |

- `stdgCd`는 10자리 숫자 문자열(아니면 `400`, KLID 전용). `cityCode`/`routeNo`는 TAGO
  값 그대로(문자열) — 도시코드는 `getCtyCodeList`로, 노선번호는 사용자가 아는 버스 번호
  그대로 입력한다. 구현된 마스터 동기화 두 개(`bus-route`, `intersections`)는 필수 파라미터가
  있고, 계약 단계인 폴링 1회 실행은 생략 시 활성 경로에서 계산한 대상 전부에 대해 실행
- 응답(동기, 구현): 잡별 카운트 객체 `{ "fetched", "created", "updated", "skipped" }`를 대상 테이블마다 돌려준다.
  `bus-route` → `{ "cityCode": "...", "routeNo": "...", "routeIds": ["..."], "lines": {…}, "stops": {…}, "lineStops": {…} }`,
  `intersections` → `{ "stdgCd": "1100000000", "intersections": {…} }`.
  `bus-route`가 `routeNo`에 매칭되는 TAGO 노선을 못 찾으면(`totalCount=0`) `404`, 여러 개 매칭되면
  (같은 도시에 같은 번호가 지선/직행 등으로 여러 개인 경우) 전부 등록하고 `routeIds`에 각각의 `routeId`를
  나열한다 — 이때 카운트는 매칭된 노선 전체의 합계다. `getRouteNoList`는 부분일치도 돌려주므로
  번호가 정확히 같은 노선만 등록하고, 정확히 같은 것이 하나도 없을 때만 검색 결과를 그대로 쓴다
- upsert 키: 노선 `(mode, stdgCd, externalId)` = `(BUS, cityCode, routeId)`, 정류장
  `(mode, stdgCd, externalId)` = `(BUS, cityCode, nodeId)`, 교차로 `(stdgCd, crsrdId)` (KLID, 이전과 동일).
  `stdgCd` 컬럼에 버스는 TAGO `cityCode`, 신호등은 KLID 법정동 코드가 들어가므로 값의 코드
  체계가 다르다는 점에 주의(둘 다 `mode`/도메인이 다르므로 섞이지 않는다). 같은 데이터를 두 번
  돌려도 결과가 같다
- 키가 비어 있으면(TAGO `TAGO_BUS_API`, KLID `REALTIME_TREFFIC_LIGHT_API`) `503`, TAGO가
  `resultCode != "00"`이거나 KLID가 `K`-오류 코드나 비JSON을 주면 `502` + `tagoResultCode`/`klidResultCode`
- 실시간 폴링 스케줄러는 `wio.polling.enabled=true`일 때만 돌고, 위 `bus-arrivals`/`signal-states`
  잡을 창(`wio.polling.windows`) 안에서 `interval-ms`마다 활성 구간/지자체에 대해 실행하는 것과 같다

## 운영 조회 (#76) — 관리 API

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/admin/ops/external-apis` | 외부 API(TAGO/KLID) 호출 집계와 일 한도 사용률, 폴링·보관 배치 상태 |

같은 `X-Api-Token`으로 보호된다(없거나 틀리면 `401`). Actuator 메트릭 엔드포인트는 인증 없이 열리므로 노출하지 않고
이 API로만 본다. 집계는 서버 메모리에만 있어 **재시작하면 비어 시작한다** — `collectingSince`가 그 시각이다.

```json
{
  "generatedAt": "2026-10-02T00:30:00Z",
  "todayStart": "2026-10-01T15:00:00Z",
  "collectingSince": "2026-10-01T23:00:00Z",
  "sources": [
    { "source": "tago", "configured": true, "ops": [
      { "op": "getRouteNoList",
        "today":    { "calls": 4, "failures": 4, "failureRate": 1.0, "p50Ms": 120, "p95Ms": 240,
                      "outcomes": { "success": 0, "httpError": 0, "apiError": 4, "timeout": 0, "ioError": 0 } },
        "lastHour": { "calls": 0, "failures": 0, "outcomes": { "success": 0, "httpError": 0, "apiError": 0, "timeout": 0, "ioError": 0 } },
        "quota": { "dailyLimit": 1000, "used": 4, "usageRate": 0.004 } } ] },
    { "source": "klid", "configured": true, "ops": [ … ] }
  ],
  "polling": { "enabled": true, "intervalMs": 60000, "windows": ["06:30-09:30", "17:30-20:30"], "withinWindow": true,
               "lastRun": { "at": "…", "result": "SUCCESS", "legsPredicted": 2, "predictions": 5,
                            "signalStatesInserted": 0, "failedCodes": ["31240"] } },
  "retention": { "enabled": true, "dryRun": false, "cron": "0 30 4 * * *", "nextRunAt": "2026-10-02T19:30:00Z",
                 "lastRun": { "at": "…", "result": "SUCCESS", "dryRun": false,
                              "rows": [ { "table": "gps_traces", "rows": 120 }, … ] } }
}
```

- 단위는 **HTTP 시도**(재시도 포함). `outcome`: `success`(KLID `K3` 포함) / `http_error`(2xx 아님 + 결과 코드 없음) /
  `api_error`(결과 코드 비정상 — HTTP 상태와 상관없이, 2xx인데 봉투를 못 읽은 것 포함) / `timeout` / `io_error`
- 오늘 = KST 자정부터, 최근 1시간 = 분 단위 60칸(지금 분 포함, 59~60분). 호출이 없는 창은 `failureRate`·`p50Ms`·`p95Ms`가 빠진다
- p50/p95는 로그 눈금 히스토그램(칸 폭 약 9%)의 nearest-rank 값을 관측 최소·최대로 자른 것이다
- `ops`에는 이 앱이 부르는 op가 호출 전에도 0으로 나오고, 그 밖에 기록된 op가 뒤에 붙는다
- `quota.dailyLimit`은 op별 설정값(`wio.tago.daily-limit` 1,000 / `wio.klid.daily-limit` 5,000, `…-overrides.<op>`로 덮어쓰기).
  `usageRate`는 1을 넘을 수 있다. 사용률은 보여 주기만 하고 호출을 막지 않는다
- `polling.lastRun.result`는 사이클이 예외 없이 끝났으면 `SUCCESS`이고, 일부 도시/지자체 실패는 `failedCodes`로 따로 보인다.
  `lastRun`이 없으면 재시작 뒤 아직 실행되지 않았다

## 정적 시간표

GTX처럼 실시간 API가 없는 노선(`has_realtime_api=false`)은 `transit_schedules`의 정적 시간표가
유일한 근거다 ([ALGORITHM.md](./ALGORITHM.md) 2.2). 예전에는 "API가 아니라 CSV 수동 import"로
적어 뒀지만, 배포된 서버에 셸 없이 넣을 수 있어야 실용적이라 **관리 엔드포인트(multipart CSV)**로 바꿨다.

### `POST /admin/schedules/import`
파트명 `file`, UTF-8 CSV. 컬럼은
`transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time`이고 헤더 행이 있으면 건너뛴다.

```csv
transit_line_id,transit_stop_id,day_type,direction_code,scheduled_time
12,45,WEEKDAY,UP,23:30
12,45,WEEKDAY,DN,23:36
12,45,SATURDAY,UP,05:30
```

- 식별자는 **내부 id**다. 수동 등록(`POST /transit-lines`, `POST /transit-stops`)은 `externalId`가
  선택 값이라 NULL일 수 있고, 그러면 외부 ID로는 지정할 수 없다. id는 노선/정류장 등록 응답과
  `/transit-lines/search`로 얻는다
  (GTX-A는 시드가 `L09`/`X108`~`X111`을 넣어 두므로 이 경우엔 해당하지 않는다)
- `day_type`은 `WEEKDAY|SATURDAY|SUNDAY_HOLIDAY`, `scheduled_time`은 `HH:mm` 또는 `HH:mm:ss`이며 **KST 벽시계**다
- `direction_code`는 `transit_line_stops.direction_code`와 **같은 어휘**다 (KLID/TAGO가 주는 코드 그대로,
  GTX-A는 `UP`=수서 방면 / `DN`=동탄 방면). 사업자마다 값이 달라 서버는 목록을 검사하지 않고 비어 있는지만 본다
- **방향이 없는 구 4컬럼 CSV는 `400`이다** (`row 2: expected 5 columns ...`). 한 정류장에는 상·하행이 같이
  서므로, 빠진 방향을 서버가 추측해서 넣는 것보다 거부하는 쪽이 낫다
- 멱등성은 **(노선, 정류장, day_type, 방향) 단위 교체**다. 파일에 나오는 조합의 기존 행을 지우고 파일 내용을
  넣는다. 같은 파일을 두 번 넣으면 행 수가 같고, 개정으로 없어진 차편은 사라진다. 파일에 없는 조합은
  건드리지 않는다 — 상행만 다시 넣어도 같은 정류장의 하행 행은 그대로 남는다
- 응답은 다른 동기화 API와 같은 `{ "fetched", "created", "updated", "skipped" }` — `fetched`는 데이터 행 수,
  `updated`는 이미 같은 시각으로 있던 차편, `skipped`는 파일 안 중복 행
- 검증 실패는 `400`이고 `detail`에 **파일 행 번호**가 들어간다 (`row 3: unknown transit_stop_id 999999`).
  한 행이라도 틀리면 전체가 들어가지 않는다

### `GET /transit-lines/{id}/schedules/next?stopId=&direction=&at=&limit=`
`at`은 ISO-8601 절대 시각(생략 시 현재), `limit`은 기본 5 · 최대 50(범위 밖이면 `400`).
노선/정류장이 없으면 `404`.

`direction`은 **필수 파라미터**다. 한 정류장에는 상·하행이 같이 서므로 생략하면 반대 방향 차가
"다음 차"로 섞인다. 서버가 대신 추측하지 않고 호출자가 정한다 — 구간(승차→하차)에서 방향을 뽑는
쪽은 백엔드의 `LegDirectionResolver`(`transit_line_stops`의 `seq_no` 순서로 판정)이고, 이 엔드포인트는
그 결과를 받는 자리다. 값 어휘는 import CSV의 `direction_code`와 같다. 빠지면 `400`.

```json
{
  "transitLineId": 12, "stopId": 45, "directionCode": "UP",
  "departures": [
    { "serviceDate": "2026-05-01", "dayType": "WEEKDAY", "scheduledTime": "23:30:00", "departureAt": "2026-05-01T14:30:00Z" },
    { "serviceDate": "2026-05-02", "dayType": "SATURDAY", "scheduledTime": "05:30:00", "departureAt": "2026-05-01T20:30:00Z" }
  ]
}
```

- 저장된 시간표는 KST 하루 중 시각이라, 서버가 **운행일을 붙여 절대 시각(`departureAt`, UTC)으로 바꿔**
  돌려준다. `serviceDate`/`scheduledTime`은 확인용 KST 값이다
- **자정 넘김**: 오늘 남은 차편이 `limit`보다 적으면 다음 날 00:00부터 이어서 채운다. 다음 날은
  `day_type`이 다를 수 있으므로(금→토, 일→월, 공휴일 전날) 날짜별로 다시 판정한다
- `day_type` 판정은 KST 날짜 기준이고, 공휴일은 리소스 파일(`calendar/kr-holidays.txt`)의 수동 목록이다
  (연 1회 갱신). 일요·공휴일 → `SUNDAY_HOLIDAY`, 토요 → `SATURDAY`, 나머지 → `WEEKDAY`
- 응답의 `directionCode`는 요청한 방향을 그대로 돌려준다. 담긴 차편은 전부 그 방향이다
- 이 조회는 적재 확인과 소비자용 원재료다. 추천 계산의 `predicted_at` fallback 배선은 별도 작업이다 (ALGORITHM.md 2.2)

## 캘리브레이션 상태 조회 (데스크탑, 디버깅/신뢰도 확인용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/commute-routes/{id}/calibration` | 경로의 구간별 보정값(도보 속도·예측 오차·차내 시간)과 샘플 수 |

샘플 수가 적으면 "아직 데이터가 부족해서 추천 신뢰도가 낮다"는 걸 UI에서 보여주기 위함.
처음 계약은 구간·노선마다 따로 부르는 두 개(`/route-legs/{id}/walking-profile`,
`/transit-lines/{id}/bias?stopId=`)였는데, 화면이 경로 하나를 그리려면 요청이 구간 수만큼 들어 #60에서 경로 단위
하나로 바꿨다. 보정 테이블은 analytics `calibrate`(#45)가 쓰고 backend는 **읽기만** 한다.

```json
{
  "routeId": 1,
  "minSamples": 5,
  "globalWalkingProfile": { "avgSpeedMps": 1.25, "stddevSpeedMps": 0.2, "sampleCount": 30, "updatedAt": "2026-09-30T18:00:00Z" },
  "legs": [
    { "routeLegId": 1, "seqOrder": 1, "legType": "WALK", "plannedDistanceM": 650.0, "plannedTravelSec": 480,
      "walkingProfile": { "avgSpeedMps": 1.4, "stddevSpeedMps": 0.1, "sampleCount": 3, "updatedAt": "2026-09-30T18:00:00Z" },
      "predictionRows": [], "travelTimeRows": [] },
    { "routeLegId": 2, "seqOrder": 2, "legType": "TRANSIT", "plannedTravelSec": 1200,
      "transitLineId": 1, "transitLineName": "GTX-A", "boardStopId": 4, "boardStopName": "동탄",
      "alightStopId": 1, "alightStopName": "수서",
      "predictionRows": [
        { "dayType": "WEEKDAY", "timeBandStart": "07:30", "timeBandEnd": "08:00",
          "biasSec": 20, "stddevSec": 45, "sampleCount": 12, "updatedAt": "2026-09-30T18:00:00Z" }
      ],
      "travelTimeRows": [
        { "dayType": "WEEKDAY", "timeBandStart": "23:30", "timeBandEnd": "24:00",
          "meanSec": 1180, "stddevSec": 60, "sampleCount": 2, "updatedAt": "2026-09-30T18:00:00Z" }
      ] }
  ]
}
```

- 경로가 없거나 내 것이 아니면 `404`(다른 경로 조회와 같다). 구간은 `seqOrder` 순
- `minSamples`는 analytics `model/lookup.py`의 `MIN_CALIBRATION_SAMPLES`와 같은 값이다. 샘플이 그보다 적은
  행은 추천에 쓰이지 않고 한 단계 위로 내려간다 — 도보: 구간 행 → `globalWalkingProfile` → 기본값 1.2 ± 0.15 m/s,
  예측 오차·차내 시간: 그 day_type·밴드 행 → 같은 노선·정류장(쌍)의 모든 행을 합친 값 → 기본값(0 ± 90초 /
  `plannedTravelSec` ± 15%) ([ALGORITHM.md](./ALGORITHM.md) 5절). **어느 단계가 쓰일지는 화면이 판단한다**
  (desktop `src/calibration/chain.ts`) — 서버는 행을 있는 그대로 준다
- WALK 구간: `walkingProfile`은 그 구간의 `user_walking_profile` 행, `globalWalkingProfile`(최상위에 한 번)은
  사용자 전역 행(`route_leg_id IS NULL`). 없으면 키째로 빠진다
- TRANSIT 구간: `predictionRows`는 (노선, 승차 정류장)의 모든 day_type × 밴드 행, `travelTimeRows`는
  (노선, 승차역, 하차역)의 모든 행. day_type(`WEEKDAY` → `SATURDAY` → `SUNDAY_HOLIDAY`) → 밴드 시작 순.
  행이 없으면 빈 배열(404가 아니다). WALK 구간은 두 배열이 항상 비어 있다
- `biasSec`은 실제 − 예측(초, 양수면 늦게 옴). `meanSec`은 승차역 출발 → 하차역 도착(초)
- **밴드 표기**: KST 벽시계 30분 `[timeBandStart, timeBandEnd)`를 `"HH:mm"` 문자열로 준다. 마지막 밴드의 끝은
  DB에 `23:59:59.999999`(`TIME`은 24:00을 못 담는다, analytics `LAST_BAND_END`)로 있지만 응답에서는 `"24:00"`이다

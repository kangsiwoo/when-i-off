# API 설계 (Backend, Kotlin/Spring)

앱(Swift)과 데스크탑(TS/React)이 공용으로 쓰는 REST API. 구현된 엔드포인트의 실제 스펙은
서버의 Swagger UI(`/swagger-ui.html`, OpenAPI JSON `/v3/api-docs`)가 진실이고, 이 문서는
계약의 의도와 아직 구현 전인 부분을 적는다. 표의 **상태** 열: `✔` #10에서 구현, `계약` 초안만.

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
| 409 | UNIQUE/FK 위반 등 무결성 오류, 실측 기록이 붙은 구간을 지우는 구간 교체 | `detail`에 DB 메시지 첫 줄 또는 문제의 구간 id |
| 502 | 외부 API(KLID/TAGO)가 오류 코드/비JSON 응답 | KLID는 `klidResultCode`(`K22` 같은 결과 코드, 비JSON이면 `HTTP403` 형식), TAGO는 `tagoResultCode`(`resultCode`가 `"00"`이 아닌 경우) 확장 필드 |
| 503 | 서비스 키 미설정 (`TAGO_BUS_API` 또는 KLID `REALTIME_TREFFIC_LIGHT_API`) | 관리 동기화 API에서만 |
| 500 | 그 외 | `detail`은 항상 `"unexpected error"`, 원인은 서버 로그 |

## 경로/구간 관리 (데스크탑에서 주로 사용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/commute-routes` | 내 출퇴근 경로 목록 |
| ✔ | POST | `/commute-routes` | 경로 생성 (이름, 방향, 출발/도착 좌표) → `201` |
| ✔ | GET | `/commute-routes/{id}` | 경로 상세 (구간 + 구간별 신호등 crossing 포함) |
| ✔ | PUT | `/commute-routes/{id}/legs` | 구간 목록 교체 (순서 재정렬 포함, 아래 "구간 교체의 의미"). WALK/TRANSIT 필드 규칙은 DB CHECK와 동일, 좌표는 WGS84 범위 검증 |
| ✔ | PUT | `/route-legs/{id}/signal-crossings` | WALK 구간의 교차로 순서 전체 교체. 항목: `trafficSignalId`, `approachDir`(`nt…nw`), `signalKind`(`Bs,Bc,Lt,Pd,St,Ut`, 기본 `Pd`) |
| ✔ | POST | `/transit-lines` | 노선 수동 등록 (`mode`, `name`, `stdgCd?`, `externalId?`, `hasRealtimeApi`) |
| ✔ | GET | `/transit-lines/search?mode=BUS&keyword=` | 노선 검색 (구간 등록 시 자동완성용, `mode` 생략 가능) |
| ✔ | POST | `/transit-stops` | 정류장/역 수동 등록 |
| ✔ | GET | `/transit-stops/nearby?lat=&lng=&radiusM=&mode=` | 근처 정류장/역 (`radiusM` 기본값은 서버 설정, `mode` 생략 가능) |
| ✔ | GET | `/transit-lines/{id}/schedules/next?stopId=&at=&limit=` | 정적 시간표 기준 다음 출발 N대 (아래 "정적 시간표") |
| ✔ | POST | `/traffic-signals` | 교차로 수동 등록 (좌표 + 이름) |
| ✔ | GET | `/traffic-signals/nearby?lat=&lng=&radiusM=` | 근처 교차로 |

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
| ✔ | POST | `/commute-trips` | 이동 시작 (`routeId`, `tripDate`, `leftHomeAt?`) → `201` |
| ✔ | PATCH | `/commute-trips/{id}` | `leftHomeAt`, `arrivedDestinationAt` 갱신 |
| ✔ | GET | `/commute-trips?routeId=&from=&to=` | 이력 조회 (attempts 포함, 히스토리 화면용). `to < from`이면 400 |
| ✔ | POST | `/commute-trips/{id}/boarding-attempts` | TRANSIT 구간 탑승 시도 **upsert** (아래) |
| ✔ | PATCH | `/boarding-attempts/{id}` | 결과 갱신 (`vehicleActualDepartureAt`, `alightedAt`, `result`, `notes`) |
| ✔ | POST | `/gps-traces/batch` | GPS 포인트 배치 업로드 (아래) |

### boarding attempt의 upsert 의미
같은 trip·leg에 attempt는 하나만 존재한다(`UNIQUE (commute_trip_id, route_leg_id)`). 앱은
geofence 이벤트마다, 그리고 오프라인 후 재전송 때 같은 요청을 여러 번 보낼 수 있으므로
`POST /commute-trips/{id}/boarding-attempts`는 **`routeLegId`를 키로 upsert**한다.

- 없으면 생성 → `201 Created`, 있으면 갱신 → `200 OK`. 본문은 둘 다 attempt 전체. 같은 (trip, leg)의
  첫 요청이 동시에 둘 들어와도 진 쪽은 갱신으로 다시 처리되어 `200`을 받는다 (409가 아님)
- **요청에서 `null`(생략)인 필드는 건드리지 않는다.** 정류장 도착만 보낸 뒤 나중에 하차만 보내도
  앞의 값이 지워지지 않는다. 값을 지우는 API는 없다 (실측 기록은 지우지 않는 게 원칙)
- `routeLegId`는 그 trip의 경로에 속한 `TRANSIT` 구간이어야 한다. 아니면 400
- `alightedAt < vehicleActualDepartureAt`이면 400
- `vehicleScheduledOrPredictedAt`는 앱이 그 순간 서버에서 받은 예측 시각의 **스냅샷**이다.
  Phase 3 후속에서 생략 시 backend가 최신 `transit_arrival_observations`로 채우는 것을 계획

### GPS 배치 업로드
- 요청: `{ "tripId": 123 | null, "points": [ {recordedAt, lat, lng, speedMps?, accuracyM?}, … ] }`
- **한 요청에 1~500포인트.** 비어 있거나 500 초과면 400. 앱은 10~30초 또는 위치 변화 임계치마다
  모아서 보낸다 (배터리/네트워크 절약)
- 검증: `lat ∈ [-90, 90]`, `lng ∈ [-180, 180]`, `speedMps`/`accuracyM ≥ 0`
- **중복은 무시**: `(user_id, recorded_at)` UNIQUE + `ON CONFLICT DO NOTHING`. 응답
  `{ "accepted": n, "ignored": m }`으로 실제 삽입 수와 중복 수를 돌려주므로 앱은 재전송을
  마음 놓고 할 수 있다 (idempotent)
- `tripId`가 있으면 내 trip이어야 한다 (아니면 404). 없으면 상시 수집분으로 저장

## 추천 조회

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| ✔ | GET | `/commute-routes/{id}/recommendation?targetArrivalAt=` | 목표 도착 시각 기준 추천 출발 시각 조회 |
| ✔ | GET | `/commute-routes/{id}/recommendation/latest` | 목표 시각과 무관하게 가장 최근 계산된 추천 |
| 계약 | GET | `/commute-routes/{id}/recommendation/history` | 과거 추천과 실제 결과 비교 (모델 성능 확인용) |

`recommendation` 엔드포인트는 Backend가 직접 계산하지 않고, Analytics가 미리 계산해
`departure_recommendations`에 적재해 둔 값을 읽거나(캐시), 캐시가 없으면 즉석 계산을
Analytics 내부 API(`analytics-service:/internal/recommend`)에 위임하는 두 방식을 열어둔다.
초기 구현은 "배치로 미리 계산 + 캐시 조회"만으로 충분하다.

적재하는 쪽은 #22에서 구현됐다 — `uv run wio-analytics recommend --route-id … --target-arrival-at …`
(콜드스타트 기본값, `model_version=v1`, [analytics/README.md](../analytics/README.md)). 조회 두 개는
#24에서 붙었고(캐시 조회만, 즉석 계산 위임은 아직 없다), `history`는 아직 `계약` 그대로다.

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

## 정적 시간표

GTX처럼 실시간 API가 없는 노선(`has_realtime_api=false`)은 `transit_schedules`의 정적 시간표가
유일한 근거다 ([ALGORITHM.md](./ALGORITHM.md) 2.2). 예전에는 "API가 아니라 CSV 수동 import"로
적어 뒀지만, 배포된 서버에 셸 없이 넣을 수 있어야 실용적이라 **관리 엔드포인트(multipart CSV)**로 바꿨다.

### `POST /admin/schedules/import`
파트명 `file`, UTF-8 CSV. 컬럼은 `transit_line_id, transit_stop_id, day_type, scheduled_time`이고
헤더 행이 있으면 건너뛴다.

```csv
transit_line_id,transit_stop_id,day_type,scheduled_time
12,45,WEEKDAY,23:30
12,45,SATURDAY,05:30
```

- 식별자는 **내부 id**다. 수동 등록 행은 `external_id`가 NULL이라 외부 ID로는 지정할 수 없고,
  주 대상인 GTX가 바로 그 경우다. id는 노선/정류장 등록 응답과 `/transit-lines/search`로 얻는다
- `day_type`은 `WEEKDAY|SATURDAY|SUNDAY_HOLIDAY`, `scheduled_time`은 `HH:mm` 또는 `HH:mm:ss`이며 **KST 벽시계**다
- 멱등성은 **(노선, 정류장, day_type) 단위 교체**다. 파일에 나오는 조합의 기존 행을 지우고 파일 내용을
  넣는다. 같은 파일을 두 번 넣으면 행 수가 같고, 개정으로 없어진 차편은 사라진다. 파일에 없는 조합은
  건드리지 않는다
- 응답은 다른 동기화 API와 같은 `{ "fetched", "created", "updated", "skipped" }` — `fetched`는 데이터 행 수,
  `updated`는 이미 같은 시각으로 있던 차편, `skipped`는 파일 안 중복 행
- 검증 실패는 `400`이고 `detail`에 **파일 행 번호**가 들어간다 (`row 3: unknown transit_stop_id 999999`).
  한 행이라도 틀리면 전체가 들어가지 않는다

### `GET /transit-lines/{id}/schedules/next?stopId=&at=&limit=`
`at`은 ISO-8601 절대 시각(생략 시 현재), `limit`은 기본 5 · 최대 50(범위 밖이면 `400`).
노선/정류장이 없으면 `404`.

```json
{
  "transitLineId": 12, "stopId": 45,
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
- 이 조회는 적재 확인과 소비자용 원재료다. 추천 계산의 `predicted_at` fallback 배선은 별도 작업이다 (ALGORITHM.md 2.2)

## 캘리브레이션 상태 조회 (데스크탑, 디버깅/신뢰도 확인용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| 계약 | GET | `/route-legs/{id}/walking-profile` | 해당 구간의 도보 속도 프로필과 샘플 수 |
| 계약 | GET | `/transit-lines/{id}/bias?stopId=` | 해당 노선/정류장의 예측 오차 보정치와 샘플 수 |

샘플 수가 적으면 "아직 데이터가 부족해서 추천 신뢰도가 낮다"는 걸 UI에서 보여주기 위함.

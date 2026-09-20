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
| 계약 | GET | `/commute-routes/{id}/recommendation?targetArrivalAt=` | 목표 도착 시각 기준 추천 출발 시각 조회 |
| 계약 | GET | `/commute-routes/{id}/recommendation/latest` | 가장 최근 계산된 추천 (기본 목표 시각 사용) |
| 계약 | GET | `/commute-routes/{id}/recommendation/history` | 과거 추천과 실제 결과 비교 (모델 성능 확인용) |

`recommendation` 엔드포인트는 Backend가 직접 계산하지 않고, Analytics가 미리 계산해
`departure_recommendations`에 적재해 둔 값을 읽거나(캐시), 캐시가 없으면 즉석 계산을
Analytics 내부 API(`analytics-service:/internal/recommend`)에 위임하는 두 방식을 열어둔다.
초기 구현은 "배치로 미리 계산 + 캐시 조회"만으로 충분하다.

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
| 계약 | GET | `/admin/sync/status` | 잡별 마지막 실행 시각/결과, 폴링 활성 여부와 현재 대상 범위 |

- `stdgCd`는 10자리 숫자 문자열(아니면 `400`, KLID 전용). `cityCode`/`routeNo`는 TAGO
  값 그대로(문자열) — 도시코드는 `getCtyCodeList`로, 노선번호는 사용자가 아는 버스 번호
  그대로 입력한다. 구현된 마스터 동기화 두 개(`bus-route`, `intersections`)는 필수 파라미터가
  있고, 계약 단계인 폴링 1회 실행은 생략 시 활성 경로에서 계산한 대상 전부에 대해 실행
- 응답(동기, 구현): 잡별 카운트 객체 `{ "fetched", "created", "updated", "skipped" }`를 대상 테이블마다 돌려준다.
  `bus-route` → `{ "cityCode": "...", "routeId": "...", "lines": {…}, "stops": {…}, "lineStops": {…} }`,
  `intersections` → `{ "stdgCd": "1100000000", "intersections": {…} }`.
  `bus-route`가 `routeNo`에 매칭되는 TAGO 노선을 못 찾으면(`totalCount=0`) `404`, 여러 개 매칭되면
  (같은 도시에 같은 번호가 지선/직행 등으로 여러 개인 경우) 전부 등록하고 응답에 각각의 `routeId`를 나열
- upsert 키: 노선 `(mode, stdgCd, externalId)` = `(BUS, cityCode, routeId)`, 정류장
  `(mode, stdgCd, externalId)` = `(BUS, cityCode, nodeId)`, 교차로 `(stdgCd, crsrdId)` (KLID, 이전과 동일).
  `stdgCd` 컬럼에 버스는 TAGO `cityCode`, 신호등은 KLID 법정동 코드가 들어가므로 값의 코드
  체계가 다르다는 점에 주의(둘 다 `mode`/도메인이 다르므로 섞이지 않는다). 같은 데이터를 두 번
  돌려도 결과가 같다
- 키가 비어 있으면(TAGO `TAGO_BUS_API`, KLID `REALTIME_TREFFIC_LIGHT_API`) `503`, TAGO가
  `resultCode != "00"`이거나 KLID가 `K`-오류 코드나 비JSON을 주면 `502` + `tagoResultCode`/`klidResultCode`
- 실시간 폴링 스케줄러는 `wio.polling.enabled=true`일 때만 돌고, 위 `bus-arrivals`/`signal-states`
  잡을 창(`wio.polling.windows`) 안에서 `interval-ms`마다 활성 구간/지자체에 대해 실행하는 것과 같다

정적 시간표(GTX 등 실시간 없는 노선)는 API가 아니라 CSV 수동 import로 `transit_schedules`에 넣는다.

## 캘리브레이션 상태 조회 (데스크탑, 디버깅/신뢰도 확인용)

| 상태 | Method | Path | 설명 |
|---|---|---|---|
| 계약 | GET | `/route-legs/{id}/walking-profile` | 해당 구간의 도보 속도 프로필과 샘플 수 |
| 계약 | GET | `/transit-lines/{id}/bias?stopId=` | 해당 노선/정류장의 예측 오차 보정치와 샘플 수 |

샘플 수가 적으면 "아직 데이터가 부족해서 추천 신뢰도가 낮다"는 걸 UI에서 보여주기 위함.

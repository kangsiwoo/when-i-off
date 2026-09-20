# 개발 계획

각 Phase는 GitHub 이슈 #2~#8로 등록되어 있다. 작업 흐름(이슈 → 브랜치 → PR → 리뷰 → 머지)은
[CONVENTIONS.md](./CONVENTIONS.md)를 따른다. Phase 이슈는 상위 이슈로 두고, 실제 작업은
PR 하나 크기의 하위 이슈로 쪼개서 진행한다.

## 원칙

- **수집이 먼저다.** 이 프로젝트는 기록이 쌓여야 가치가 나온다. 캘리브레이션에 필요한
  샘플(구간별 5회 이상)을 최대한 빨리 모으기 시작하도록, 앱의 데이터 수집을 분석/화면보다
  앞에 둔다.
- 각 단계는 끝났을 때 **혼자서도 돌아가는 상태**여야 한다 (다음 단계 없이도 쓸 수 있게).
- 외부 API 없이도 동작하게 만든다. 시간표/정류장은 수동 입력으로 시작하고, 동기화는
  나중에 얹는다.

## 레포 구조 (모노레포)

```
when-i-off/
  backend/            Kotlin + Spring Boot 3.x, Spring Data JPA, Flyway
  analytics/          Python 3.12, pandas / numpy / scipy, psycopg, typer CLI
  desktop/            TypeScript + React (Vite), Leaflet
  ios/                Swift (SwiftUI, CoreLocation)
  docs/               설계 문서 (현재)
  docker-compose.yml  로컬 PostgreSQL 16
```

## 단계별 계획

### Phase 0 — 기반 (반나절~1일) · 이슈 #2

- 모노레포 폴더 구조, `docker-compose.yml`로 로컬 Postgres
- backend 스캐폴딩: Spring Boot + Kotlin, Flyway. `docs/db/schema.sql` →
  `backend/src/main/resources/db/migration/V1__init_schema.sql`
- **공공데이터포털(data.go.kr) API 키 신청을 이 단계에서 바로 한다.** 승인에 며칠 걸릴 수
  있음. 1차 소스는 KLID 전국통합데이터 두 개(초정밀버스 위치 실시간 `B551982/rte`, 교통안전
  신호등 실시간 `B551982/rti`). 특일정보도 함께
- 완료 기준: `./gradlew bootRun` 시 마이그레이션 적용, health check 통과

> **#10 backend MVP에서 완료**: 모노레포 구조, `docker-compose.yml`(Postgres 16), Gradle/Kotlin
> 스캐폴딩, Flyway `V1__init_schema.sql` + `V2__seed_default_user.sql`, GitHub Actions CI
> (`backend-ci.yml`, Postgres 서비스 컨테이너). 남은 것: 실제 키 발급.

### Phase 1 — Backend 최소 API (2~3일) · 이슈 #3

- 엔티티/리포지토리: `users`, `commute_routes`, `route_legs`, `transit_lines`,
  `transit_stops`, `traffic_signals`, `route_leg_signal_crossings`
- 경로/구간 CRUD, 노선·정류장·신호등 **수동 등록** API (동기화 전엔 직접 입력)
- `commute_trips` / `boarding_attempts` 생성·갱신, `gps_traces` 배치 업로드 (upsert,
  idempotent)
- 인증: 1인 사용이므로 고정 API 토큰(헤더)으로 시작. JWT는 필요해지면
- 완료 기준: curl로 경로 등록 → trip 시작 → attempt 기록 → 이력 조회까지 됨

> **#10 backend MVP에서 완료**: 전 테이블 JPA 엔티티/리포지토리, `X-Api-Token` 필터,
> RFC 7807 에러 핸들러, 경로/구간/신호 crossing API, 노선·정류장·교차로 수동 등록 + 검색 API,
> trip/attempt(upsert)/GPS 배치(500개, 중복 무시) API, Swagger UI. 상세는 [API.md](./API.md)의
> 상태 열.

### Phase 2 — iOS 앱: 데이터 수집 MVP (1~2주) · 이슈 #4

- SwiftUI + CoreLocation. "항상 허용" 위치 권한
- 활성 경로의 집/승차·하차 정류장/목적지에 geofence(`CLCircularRegion`) 등록.
  앱당 20개 제한이 있으므로 활성 경로 하나의 지점만 등록
- geofence 이벤트로 자동 채움:
  - 집 이탈 → `trip.left_home_at` (trip 생성)
  - 승차 정류장 진입 → `attempt.arrived_at_stop_at` (attempt 생성, 그 순간의 예측 시각
    스냅샷 저장 — Phase 3 이전엔 시간표값 또는 null)
  - 하차 정류장 진입 → `attempt.alighted_at`
  - 목적지 진입 → `trip.arrived_destination_at`
- 정류장 대기 화면: **탔음 / 놓쳤음** 버튼 (`vehicle_actual_departure_at`은 버튼 누른
  시각을 기본값으로, 수정 가능)
- 이동 중 GPS 포인트를 로컬 큐에 쌓고 배치 업로드 (오프라인/지하 구간 대비)
- 완료 기준: 실제 출근 1회로 trip + attempts + traces가 DB에 정상 적재

### Phase 3 — 외부 데이터 동기화 (3~5일) · 이슈 #5

- ~~KLID 버스 `rtm_loc_info`(차량 위치) → `bus_position_observations` → 노선 폴리라인 투영으로
  ETA 파생~~ — KLID 버스 실시간 위치가 화성·성남·서울 세 곳 모두 0건으로 확인되어(#10, #12)
  폐기. TAGO 버스도착정보(`15098530`)가 정류장 단위 도착예측을 직접 주므로 이 경로 자체가
  필요 없어졌다 ([ADR 0001](./adr/0001-tago-bus-arrival-prediction.md), #13). TAGO
  `getRouteNoList`/`getRouteAcctoThrghSttnList`(노선 검색 + 경유 정류장) →
  `transit_lines`/`transit_stops`/`transit_line_stops`, `getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList`
  (도착예측) → `transit_arrival_observations`
- KLID 신호등 `tl_drct_info` → `traffic_signal_states` (서울·울산 커버). 마스터
  `crsrd_map_info` → `traffic_signals`
- KLID 신호등은 **필터가 `stdgCd`(지자체)뿐이므로 폴링 범위를 활성 경로의 지자체 집합 ×
  출퇴근 창(`wio.polling.windows`)으로 제한**해서 일일 호출 한도(개발계정 일 5,000회 수준)
  안에서 운영. TAGO 버스는 노선/정류장 단위로 정확히 조회하므로 지자체 크기와 무관하게
  활성 구간 수만큼만 호출한다. 상세는 ARCHITECTURE.md "외부 데이터 동기화"
- **TAGO 실 키를 받는 첫 세션**: fixture가 포털 Swagger 샘플로만 만들어졌으므로 실 응답으로
  필드/커버리지를 재확인한다 ([backend/README.md](../backend/README.md) 체크리스트).
  화성/동탄 권역 커버리지가 비어 있으면 경기 GBIS 병행을 다시 검토 (ADR 0001 "후속")
- 지하철 실시간 도착정보 → 같은 `ArrivalPredictionProvider`로 추가
- ✔ 정적 시간표 import: GTX 등 실시간 없는 노선은 CSV를 `POST /admin/schedules/import`로 올려
  `transit_schedules`에 적재(조합 단위 교체로 멱등), `GET /transit-lines/{id}/schedules/next`로
  자정을 넘겨 다음 N대를 절대 시각으로 조회. 추천 계산에 fallback으로 꽂는 것은 다음 작업
- ✔ 공휴일 캘린더: 특일정보 API 대신 리소스 파일(`calendar/kr-holidays.txt`) **연 1회 수동** +
  `DayTypeResolver`로 `date → day_type` 매핑
- 신호 주기: 공공 데이터가 있으면 연동, 없으면 desktop에서 수동 입력(`USER_OBSERVED`)
- 앱의 attempt 생성 시 예측 스냅샷을 최신 observation에서 채우도록 backend 연결
- 완료 기준: 출근 시간대에 observations가 30초~1분 간격으로 쌓이고, attempt에 예측
  스냅샷이 자동으로 들어감

> **#10 backend MVP에서 착수**: 스키마에 `transit_line_stops`, `bus_position_observations`,
> `traffic_signal_states`, `stdg_cd`, crossing의 `approach_dir`/`signal_kind` 반영, KLID 설정 키
> (`wio.klid.*`, `wio.polling.*`), 관리 동기화 API 계약. 키 없이 진행했으므로 응답 포맷은 포털
> Swagger + 기록된 샘플 기준이며 실제 응답으로 fixture를 갱신해야 한다.

### Phase 4 — Analytics v1 (1주) · 이슈 #6

- CLI 서브커맨드: `derive-walking-segments`, `calibrate`, `recommend`
- `derive-walking-segments`: DATA_MODEL.md 규칙으로 trip → `walking_segments` 파생
- `calibrate`: `user_walking_profile`, `transit_prediction_calibration`,
  `transit_travel_time_calibration` 갱신 (샘플 5회 미만 그룹은 상위 그룹 상속)
- `recommend`: ALGORITHM.md 3절 역산 구현 → `departure_recommendations`. 콜드스타트
  기본값 포함
- 스케줄: cron. 새벽에 `derive` + `calibrate`, 출근 목표 시각 2시간 전부터 5~10분 간격으로
  `recommend` (실시간 예측이 갱신되므로)
- 테스트: 합성 데이터로 분위수 방향(`1−p`), 역산 순서, 후보 차량 선택 단위 테스트
- 완료 기준: 실제 기록 5일치로 추천이 나오고, 기록이 늘수록 `buffer_seconds`가 줄어드는
  것을 확인

### Phase 5 — 데스크탑 웹 (1주) · 이슈 #7

- 지도(Leaflet + OSM) 위에서 경로/구간/정류장/신호등 편집
- 히스토리: trip별 타임라인 (집 출발 → 정류장 → 탑승 → 하차 → 도착)
- 캘리브레이션 상태: 구간/노선별 샘플 수와 표준편차 → "신뢰도 낮음" 표시
- 추천 vs 실제 비교 차트 (`departure_recommendations` ↔ `commute_trips`)
- 완료 기준: 앱 없이 경로 등록·수정, 쌓인 데이터 검증 가능

### Phase 6 — 피드백 루프 & 운영 (지속) · 이슈 #8

- 앱 알림: 추천 시각에 "지금 나가세요" (로컬 알림으로 시작, 필요하면 APNs)
- 추천 대비 실제 결과 자동 평가 → `model_version`별 성공률 추적
- 데이터 보관: `gps_traces`는 90일 후 삭제(`walking_segments`로 요약 완료된 것만),
  `transit_arrival_observations`는 1년
- 이 시점에 앱의 실시간 폴링이 늘어 공공 API 한도가 문제 되면 Redis(짧은 TTL 캐시) 검토

## 리스크 / 미리 알아둘 것

| 리스크 | 대응 |
|---|---|
| 공공 API 키 승인 지연, 일일 호출 한도 | Phase 0에서 즉시 신청. 폴링 범위를 활성 경로의 지자체·시간대로 한정 (`stdgCd`가 유일한 필터라 노선 단위로 줄일 수 없음) |
| KLID 버스 커버리지 부족 (화성·성남·서울 실측 0건, #10) | 버스는 TAGO 버스도착정보(15098530)로 교체 완료(ADR 0001, #13). TAGO 실 키로 화성/동탄 권역 재확인 필요 — 비어 있으면 경기 GBIS 병행 검토. 신호는 서울 `crsrd_map_info` K0/2779건 커버 확인됨(#10), 커버 밖이면 주기 모델 fallback이 이미 있음 |
| iOS 백그라운드 위치 제약 (geofence 20개, 정확도 저하) | 활성 경로 1개 지점만 등록. 지하 역사에서 GPS 유실 시 `alighted_at`이 지상에서 늦게 잡힐 수 있음 → 이동시간 보정에 노이즈, 사용자가 수정 가능하게 |
| GTX 실시간 API 부재 | 시간표 + 실측 보정만으로 시작 (`has_realtime_api=false`) |
| 콜드스타트 | 처음 1~2주는 추천이 보수적(일찍 나가라)임을 UI에 명시 |
| 광역버스 만석 통과 | `result=MISSED`지만 원인이 다름. v1에선 구분 없이 `notes`에 기록, 빈도가 높으면 `miss_reason` 컬럼 추가 |
| 환승 대기 등 "기다리는 시간"과 "걷는 시간" 혼재 | `walking_segments`는 인접 사건 간 전체 시간을 담으므로 정류장 대기까지 포함됨. v1은 그대로 두고, 필요하면 GPS 정지 구간을 분리 |

## 순서 요약

```
0 기반 → 1 백엔드 최소 → 2 앱 수집 → 3 외부 동기화 → 4 분석 → 5 웹 → 6 운영
```

2와 3은 순서를 바꿔도 되지만, 2를 먼저 하면 3이 끝날 때쯤 이미 캘리브레이션에 쓸 실측
기록이 몇 주치 쌓여 있다는 이점이 있다.

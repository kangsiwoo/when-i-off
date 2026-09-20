# 0001. 버스 도착예측을 TAGO로 교체 (KLID `rte` 폐기)

## 배경
KLID 초정밀버스 위치 실시간 정보(`rte/mst_info`, `rte/rtm_loc_info`)로 대상 지자체 세 곳
(화성 `4159000000`, 성남 `4113000000`, 서울 `1100000000`)의 커버리지를 실 키로 확인한 결과
`mst_info`(노선 마스터), `rtm_loc_info`(실시간 위치) 모두 세 곳 전부 `K3`(NODATA)/`totalCount=0`
였다 (#10 코멘트, backend/README.md 체크리스트 3번). 신호(`rti/crsrd_map_info`)는 서울
`K0`/`totalCount=2779`로 커버되므로 이 결정은 **버스에만** 해당한다.

`KlidPositionEtaProvider`(현재 유일한 `ArrivalPredictionProvider` 구현체)는 KLID 실시간
위치를 노선 폴리라인(`transit_line_stops`)에 투영해 정류장 ETA를 기하학적으로 파생시키는
방식인데, 애초에 입력이 되는 위치 데이터 자체가 없으므로 이 경로 전체가 쓸모없다.

`docs/DATA_MODEL.md`의 `transit_arrival_observations` 설계는 처음부터 "나중에 TAGO/GBIS처럼
도착예측을 직접 주는 소스로 바꾸더라도 `source` 값만 다르게 넣으면 되도록" 소스 중립으로
만들어 뒀고, `ARCHITECTURE.md`/`DEVELOPMENT_PLAN.md`도 이 교체를 Phase 3 리스크로 미리
적어 두었다. 이번 결정은 그 계획을 실행하는 것이다.

## 결정
버스 `ArrivalPredictionProvider` 구현체를 **TAGO 버스도착정보**(국토교통부, 공공데이터포털
`15098530`, `ArvlInfoInqireService`)로 교체한다. 경기 GBIS는 후보에서 제외한다.

### GBIS를 제외한 이유
GBIS(경기도 버스정보시스템)는 **경기도 버스만** 커버한다. 대상 지자체 세 곳 중 서울은
경기도가 아니므로 GBIS 단독으로는 세 지자체를 하나의 클라이언트/스키마로 덮을 수 없다.
반면 TAGO는 전국 단일 게이트웨이(KLID와 같은 성격)라 화성·성남·서울을 같은 방식으로
다룰 수 있고, 이는 KLID를 1차 소스로 골랐던 이유("클라이언트 하나로 두 도메인/지역을 덮을
수 있다")와 같은 원칙이다. TAGO의 실제 커버리지(특히 화성/동탄 권역 노선)는 아직 실 키로
검증하지 못했으므로 완전히 확정된 것은 아니지만, 구조적으로 서울을 아예 못 덮는 GBIS보다
우선순위가 높다. GBIS 키는 향후 TAGO의 특정 노선 커버리지가 부족할 때 보강용으로 고려할 수
있다 (아래 "후속" 참고).

## 아키텍처 변화

### 1. 마스터 데이터 취득 방식이 바뀐다
KLID `rte/mst_info`·`ps_info`는 `stdgCd`(지자체) 단위로 **전체를 벌크로 받아 메모리에서
필터**하는 방식이었다. TAGO 버스노선정보(`BusRouteInfoInqireService`)에는 그런 지자체
전체 덤프 오퍼레이션이 없고, **노선번호로 검색해 `routeId`를 찾은 뒤(`getRouteNoList`),
그 노선의 경유 정류소 순서를 조회(`getRouteAcctoThrghSttnList`)**하는 식으로 동작한다.
따라서 새 마스터 동기화는 "지자체 전체 upsert" 대신 "노선 하나를 검색해서 등록"하는
관리 API가 된다 (기존 수동 등록 흐름, API.md의 "수동 등록은 KLID 마스터 동기화가 커버하지
않는 곳을 위한 것" 과 사실상 가까워진다).

### 2. 위치→ETA 기하 계산이 없어진다
TAGO는 정류장 단위로 **도착예측(도착까지 남은 시간)을 직접** 주므로
(`getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList`, 파라미터 `cityCode`+`nodeId`+`routeId`),
`KlidPositionEtaProvider`의 폴리라인 투영/속도 기반 ETA 계산이 필요 없다. 새 구현체
(`TagoArrivalPredictionProvider`)는 TAGO 응답의 `arrtime`(초)을 그대로 `predictedArrivalAt`
계산에 쓴다.

이에 따라 버스 실시간 **위치** 원본을 쌓던 `bus_position_observations`/
`BusPositionSnapshotCache`/`KlidBusApi.rtmLocInfo`는 더 이상 쓰지 않는다. 테이블/코드를
남겨서 나중에 위치 기반 방식으로 되돌아갈 수 있게 하기보다는, TAGO가 위치가 아니라
예측을 직접 주는 이상 이 경로는 죽은 코드이므로 **삭제**한다 (마이그레이션은 이미 머지된
`V1__init_schema.sql`을 손대지 않는 컨벤션에 따라 테이블 자체는 남기되 코드에서 쓰지 않음).

### 3. `transit_lines`/`transit_stops`의 외부 ID 의미가 바뀐다
스키마(`(mode, stdg_cd, external_id)` UNIQUE)는 그대로 재사용하지만 값의 의미가 다르다:
- `stdg_cd`: KLID 법정동 시도코드(10자리) 대신 **TAGO `cityCode`**를 넣는다. 서로 다른
  코드 체계이므로 같은 컬럼에 두 값 체계가 섞이지 않도록 버스(`mode=BUS`)는 전량 TAGO
  cityCode로, KLID 버스 데이터는 애초에 0건이라 기존 값과 충돌하지 않는다.
- `external_id`: 노선은 TAGO `routeId`, 정류장은 TAGO `nodeId`.
- 신호(`traffic_signals.stdg_cd`/`crsrd_id`)는 계속 KLID `stdgCd`/`crsrdId` 그대로다 —
  이번 결정과 무관.

### 4. `Prediction.vehicleNo`를 nullable로 바꾼다
TAGO 도착예측 응답은 차량 번호(차대/차량 식별자)를 주지 않는다(`vehicletp`=차량유형 코드만
있음). `transit_arrival_observations.vehicle_no`/`TransitArrivalObservation.vehicleNo`는
이미 nullable이므로 `ArrivalPredictionProvider.Prediction.vehicleNo: String?`로 바꿔도
스키마 변경이 필요 없다.

### 5. `source` 값
`transit_arrival_observations.source = "TAGO_ARVL"` (기존 `KLID_RTM_LOC_ETA`를 대체).

## 대안 검토
- **GBIS만 사용**: 기각 (위 참고 — 서울 미커버)
- **TAGO + GBIS 병행** (지자체별로 다른 소스 선택): 지금은 과도한 복잡도. TAGO 커버리지가
  실 키로 확인됐을 때 화성/동탄 권역이 비면 그때 GBIS를 보조 소스로 추가하는 것으로 미룬다
- **KLID 위치→ETA 경로 유지 + TAGO 병행**: 기각 — 입력 데이터(KLID 위치)가 세 지자체 모두
  0건이라 유지할 이유가 없다. 코드만 늘어난다

## 결과
- `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/DATA_MODEL.md`, `docs/DEVELOPMENT_PLAN.md`,
  `backend/README.md`를 이 PR에서 함께 갱신한다
- `KlidPositionEtaProvider`, `BusPositionSnapshotCache`, `BusPositionObservation`(엔티티/
  레포지토리)를 삭제한다
- `KlidMasterSyncService`/`AdminSyncController`의 버스 마스터 동기화(`/bus-master`)를
  TAGO 기반(`TagoMasterSyncService`, 노선번호 검색)으로 교체한다. 신호 동기화
  (`/intersections`)는 완전히 그대로 둔다
- `KlidBusApi`(`mstInfo`/`psInfo`/`rtmLocInfo`, `rte` 서비스 전체)는 위 교체로 호출하는
  곳이 하나도 남지 않으므로 **클래스 전체와 `wio.klid.bus`/`PRECISE_BUS_API` 설정까지
  함께 삭제**한다. 죽은 코드를 "나중에 쓸 수도 있으니" 남겨 두지 않는다 — KLID 신호등
  (`KlidSignalApi`, `wio.klid.signal`/`REALTIME_TREFFIC_LIGHT_API`)은 완전히 그대로 둔다

## 후속 (이 PR 범위 밖)
- TAGO 실 키 발급(공공데이터포털 활용신청) — 사람이 해야 하는 외부 작업. 키가 없으면
  `TAGO_BUS_ARVL_API`(가칭)가 빈 문자열이라 기존 KLID와 같은 방식으로 503을 낸다
- 실 키로 화성/동탄 권역 커버리지 재확인. 비어 있으면 GBIS 병행을 다시 검토
- TAGO fixture는 공공데이터포털 API 문서(Swagger)의 필드 설명만으로 작성했고 실 응답으로
  검증되지 않았다 — KLID 때와 같은 패턴("실제 키를 받은 첫 세션의 확인 체크리스트")을
  `backend/README.md`에 TAGO용으로 추가해 둔다

# backend

Kotlin + Spring Boot 3.5, JDK 21, PostgreSQL 16, Flyway. 설계는 [`docs/`](../docs) 참고
(특히 [API.md](../docs/API.md), [DATA_MODEL.md](../docs/DATA_MODEL.md), [ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 로컬 실행

```bash
# 1. Postgres (레포 루트의 docker-compose.yml, DB when_i_off / wio / wio)
docker compose up -d

# 2. 환경변수. 루트의 .env.example을 복사해서 채운다
cp .env.example .env          # WIO_API_TOKEN은 아무 문자열, TAGO/KLID 키는 있으면 그대로 넣는다
set -a; . ./.env; set +a      # 또는 IDE 실행 설정에 넣는다

# 3. 실행 (부팅 시 Flyway가 V1 스키마 + V2 기본 사용자를 적용)
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

- Health: `http://localhost:8080/actuator/health` (인증 없음)
- Swagger UI: `http://localhost:8080/swagger-ui.html` (OpenAPI JSON `/v3/api-docs`)
- API 호출은 전부 `X-Api-Token: $WIO_API_TOKEN` 헤더 필요:
  ```bash
  curl -s -H "X-Api-Token: $WIO_API_TOKEN" localhost:8080/api/v1/commute-routes
  ```

설정 키(`src/main/resources/application.yml`):

| 키 | 환경변수 | 기본값 | 의미 |
|---|---|---|---|
| `spring.datasource.*` | `WIO_DB_URL`, `WIO_DB_USER`, `WIO_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/when_i_off`, `wio`/`wio` | DB |
| `wio.api-token` | `WIO_API_TOKEN` | (필수) | 고정 API 토큰 |
| `wio.tago.service-key` | `TAGO_BUS_API` | 빈 문자열 | TAGO 버스노선정보/버스도착정보 공용 서비스 키 (Decoding/Encoding 둘 다 가능) |
| `wio.tago.connect-timeout` / `read-timeout` / `max-retries` / `retry-backoff` | | 5s / 20s / 2 / 500ms | TAGO 클라이언트 |
| `wio.klid.signal.service-key` | `REALTIME_TREFFIC_LIGHT_API` | 빈 문자열 | KLID 신호등 `rti` 서비스 키 (Decoding/Encoding 둘 다 가능) |
| `wio.klid.connect-timeout` / `read-timeout` / `max-retries` / `retry-backoff` | | 5s / 20s / 2 / 500ms | KLID 클라이언트 |
| `wio.polling.enabled` | | `false` | 실시간 폴링 스케줄러 on/off |
| `wio.polling.interval-ms` | | 60000 | 폴링 간격 |
| `wio.polling.windows` | | `06:30-09:30,17:30-20:30` | 폴링하는 시간대 (KST) |

키는 환경변수로만 받는다. 값은 커밋·로그·채팅에 남기지 않는다.

## 테스트

`./gradlew check`가 CI 게이트다 (ktlint + 테스트). 커밋 전에 `./gradlew ktlintFormat`을 먼저 돌린다.

통합 테스트는 **Testcontainers를 쓰지 않고** 실제 로컬 PostgreSQL에 붙는다 (`test` 프로필,
Docker가 없는 환경에서도 돌게). 테스트는 Flyway `clean` 후 마이그레이션을 다시 적용하므로
**개발용 DB와 다른 DB 이름**을 쓴다.

```bash
# Docker가 있으면 compose의 Postgres에 테스트용 DB만 하나 더 만든다
docker compose exec postgres psql -U wio -d when_i_off -c "CREATE DATABASE when_i_off_test OWNER wio;"

# Docker가 없으면 (예: 리눅스에 postgresql 패키지 설치)
service postgresql start
su postgres -c "psql -c \"CREATE USER wio WITH PASSWORD 'wio' CREATEDB;\""
su postgres -c "psql -c \"CREATE DATABASE when_i_off_test OWNER wio;\""

# 실행
cd backend
WIO_DB_URL=jdbc:postgresql://localhost:5432/when_i_off_test WIO_DB_USER=wio WIO_DB_PASSWORD=wio \
  ./gradlew ktlintFormat check
```

- 외부 API(TAGO/KLID)는 테스트에서 절대 호출하지 않는다. 응답 fixture + okhttp `MockWebServer`로 대체
- 테스트 리포트: `build/reports/tests/test/index.html`

## TAGO(버스)/KLID(신호등) 동기화 — 마스터 + 폴링 how-to

버스는 TAGO, 신호등은 KLID를 쓴다 (왜 나뉘었는지는 [ADR 0001](../docs/adr/0001-tago-bus-arrival-prediction.md)).
공공데이터포털에서 각각 활용신청:
- 버스: 국토교통부 (TAGO) 버스도착정보(`15098530`) + 버스노선정보(`15098529`, 같은 계정 키로
  같이 쓸 수 있는 경우가 많다) → `TAGO_BUS_API`
- 신호등: KLID 교통안전 신호등 실시간 정보 (`apis.data.go.kr/B551982/rti`) → `REALTIME_TREFFIC_LIGHT_API`

TAGO는 노선번호(`routeNo`)+도시코드(`cityCode`)로 노선 하나를 검색해서 등록하는 방식이고,
KLID 신호등은 `stdgCd`(법정동 시도코드 10자리) 단위로 그 지자체 전체를 받는다. 자주 쓰는
KLID `stdgCd`: 서울 `1100000000`, 성남 `4113000000`, 화성 `4159000000`. TAGO `cityCode`는
`getCtyCodeList`로 조회한다(코드 체계가 KLID `stdgCd`와 다르다).

```bash
T="X-Api-Token: $WIO_API_TOKEN"; B=localhost:8080/api/v1/admin/sync

# 1. 버스 노선 등록: 도시코드 + 노선번호로 검색해 노선/정류장/경유순서를 upsert
curl -s -X POST -H "$T" "$B/tago/bus-route?cityCode=<TAGO cityCode>&routeNo=<노선번호>"
# 2. 신호등 마스터: 교차로 (crsrd_map_info) → traffic_signals
curl -s -X POST -H "$T" "$B/klid/intersections?stdgCd=1100000000"
# 3. 데스크탑/curl로 경로를 등록한다 (노선·정류장·교차로 ID는 위 결과에서 검색)
# 4. 폴링 1회 수동 실행 (활성 구간/지자체만 대상). 응답의 fetched/upserted/skipped로 동작 확인
curl -s -X POST -H "$T" "$B/tago/bus-arrivals"
curl -s -X POST -H "$T" "$B/klid/signal-states"
# 5. 상시 폴링은 설정으로 켠다 (출퇴근 창 안에서만 돈다)
#    WIO_POLLING_ENABLED=true  또는 application.yml의 wio.polling.enabled: true
curl -s -H "$T" localhost:8080/api/v1/admin/sync/status
```

관리 API는 아직 계약 단계인 것이 있다. 현재 구현 여부는 Swagger UI가 진실 ([API.md](../docs/API.md)의 상태 열 참고).

KLID 호출 한도(개발계정 일 5,000회 수준) 안에 있으려면 활성 경로가 없는 지자체는 폴링하지
않고, 창 밖에서는 스케줄러가 쉰다. 지자체의 `totalCount`가 1,000을 넘으면 페이지 수만큼
호출이 늘어난다. TAGO는 지자체가 아니라 등록한 노선/정류장 수만큼 호출하므로 이 문제가 없다.

## 실제 키를 받은 첫 세션의 확인 체크리스트

### TAGO(버스) — 이번 세션에서 처음 받으면
키 없이 만든 코드라 응답 포맷은 포털 Swagger 문서 설명만으로 작성했다(실 응답으로 검증되지
않음). 키를 받으면 **코드를 더 쓰기 전에** 아래를 먼저 확인한다.

1. `getCtyCodeList`로 화성/성남/서울에 해당하는 TAGO `cityCode`를 확인한다 (KLID `stdgCd`와
   다른 값이다 — 혼동 주의)
2. 실제로 쓸 광역버스 노선 번호로 `getRouteNoList` → `routeId` 확인, `getRouteAcctoThrghSttnList`로
   경유 정류장이 기대한 대로 나오는지(화성/동탄 권역 정류장이 포함되는지) 확인. `getRouteNoList`는
   부분일치 검색이라 동기화는 번호가 정확히 같은 노선만 등록한다 — 원하는 노선이 걸러지지 않는지 본다
3. `getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList`로 실제 도착예측(`arrtime`)이 채워지는지
   확인 — 비어 있으면 ADR 0001 "후속"의 경기 GBIS 병행을 검토
4. 응답 하나를 `src/test/resources/fixtures/tago/`에 저장해 fixture를 실 응답으로 갱신한다
   (키·개인정보 없음 확인)
5. 키 인코딩은 KLID와 같은 규칙(Decoding/Encoding 아무거나, `%` 있으면 그대로 전송)을 따른다
6. 응답 포맷 파라미터는 `_type=json`(KLID는 `type=json`)으로 보내고 있다. 400/XML이 오면 이 이름과
   `response` 래퍼 유무부터 확인한다

### KLID(신호등)
1. 키 인코딩: `.env`에는 포털의 **Decoding**/**Encoding** 키 중 아무거나 넣어도 된다. 클라이언트가
   값에 `%`가 있으면 이미 인코딩된 키로 보고 그대로 보내고, 없으면 한 번만 인코딩한다.
   `Unauthorized`/`K30`이 오면 대부분 미승인 상태. (공공데이터포털 활용신청 참고사항: "API 환경
   또는 API 호출 조건에 따라 인증키가 적용되는 방식이 다를 수 있다" — 포털도 두 형태 중 실제로
   구동되는 키를 쓰라고 안내한다)
2. **커버리지 `totalCount` 확인**(신호는 서울 `K0`/2779건으로 이미 확인됨, #10 — 다른
   지자체를 새로 쓰게 되면 아래로 재확인):

   ```bash
   # 아래 --data-urlencode는 Decoding 키 기준. Encoding 키(값에 `%` 포함)라면
   # 이중 인코딩되므로 `-d "serviceKey=$K"`로 바꿔 그대로 실어 보낸다
   K="$REALTIME_TREFFIC_LIGHT_API"
   for cd in 4159000000 4113000000 1100000000; do
     curl -sG "https://apis.data.go.kr/B551982/rti/tl_drct_info" \
       --data-urlencode "serviceKey=$K" -d "stdgCd=$cd" -d "numOfRows=1" -d "pageNo=1" -d "type=json" \
       | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["header"]["resultCode"], d.get("body",{}).get("totalCount"))'
   done
   ```

   `K3`(NODATA)는 "그 지자체는 제공 안 함"이다. 오류가 아니다. (버스 `rte`/`rtm_loc_info`는
   세 지자체 모두 `K3`/0으로 이미 확인되어 TAGO로 교체됨 — ADR 0001, 다시 확인할 필요 없음)
3. 페이지 크기: `totalCount`가 1,000을 넘는 지자체는 폴링 1회에 여러 호출이 들어간다.
   한도 계산을 다시 한다
4. 실제 응답 하나를 `src/test/resources/fixtures/klid/`에 저장해 fixture를 갱신한다
   (키·개인정보 없음 확인). 특히 `tl_drct_info`의 96개 필드 전부

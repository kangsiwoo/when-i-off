# backend

Kotlin + Spring Boot 3.5, JDK 21, PostgreSQL 16, Flyway. 설계는 [`docs/`](../docs) 참고
(특히 [API.md](../docs/API.md), [DATA_MODEL.md](../docs/DATA_MODEL.md), [ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

## 로컬 실행

```bash
# 1. Postgres (레포 루트의 docker-compose.yml, DB when_i_off / wio / wio)
docker compose up -d

# 2. 환경변수. 루트의 .env.example을 복사해서 채운다
cp .env.example .env          # WIO_API_TOKEN은 아무 문자열, KLID 키는 있으면 "Decoding" 키
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
| `wio.klid.bus.service-key` | `REALTIME_BUS_API_KEY` | 빈 문자열 | KLID 버스 `rte` 서비스 키 (디코딩된 값) |
| `wio.klid.signal.service-key` | `TRAFFIC_SIGNAL_API_KEY` | 빈 문자열 | KLID 신호등 `rti` 서비스 키 (디코딩된 값) |
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

- 외부 API(KLID)는 테스트에서 절대 호출하지 않는다. 응답 fixture + okhttp `MockWebServer`로 대체
- 테스트 리포트: `build/reports/tests/test/index.html`

## KLID 동기화 — 마스터 + 폴링 how-to

KLID(한국지역정보개발원 전국통합데이터) 두 서비스를 쓴다. 공공데이터포털에서 각각 활용신청:
- 버스: 초정밀버스 위치 실시간 정보 (`apis.data.go.kr/B551982/rte`) → `REALTIME_BUS_API_KEY`
- 신호등: 교통안전 신호등 실시간 정보 (`apis.data.go.kr/B551982/rti`) → `TRAFFIC_SIGNAL_API_KEY`

유일한 필터는 `stdgCd`(법정동 시도코드 10자리)이고 노선/교차로 단위 조회는 없다. 자주 쓰는 코드:
서울 `1100000000`, 성남 `4113000000`, 화성 `4159000000`.

```bash
T="X-Api-Token: $WIO_API_TOKEN"; B=localhost:8080/api/v1/admin/sync/klid

# 1. 마스터: 노선 + 방향별 정류장 순서 (mst_info + ps_info) → transit_lines/stops/line_stops
curl -s -X POST -H "$T" "$B/bus-master?stdgCd=4159000000"
# 2. 마스터: 교차로 (crsrd_map_info) → traffic_signals
curl -s -X POST -H "$T" "$B/intersections?stdgCd=1100000000"
# 3. 데스크탑/curl로 경로를 등록한다 (노선·정류장·교차로 ID는 위 결과에서 검색)
# 4. 폴링 1회 수동 실행 (활성 경로의 지자체만 대상). 응답의 fetched/upserted/skipped로 동작 확인
curl -s -X POST -H "$T" "$B/bus-positions"
curl -s -X POST -H "$T" "$B/signal-states"
# 5. 상시 폴링은 설정으로 켠다 (출퇴근 창 안에서만 돈다)
#    WIO_POLLING_ENABLED=true  또는 application.yml의 wio.polling.enabled: true
curl -s -H "$T" localhost:8080/api/v1/admin/sync/status
```

관리 API는 아직 계약 단계인 것이 있다. 현재 구현 여부는 Swagger UI가 진실 ([API.md](../docs/API.md)의 상태 열 참고).

호출 한도(개발계정 일 5,000회 수준) 안에 있으려면 활성 경로가 없는 지자체는 폴링하지 않고,
창 밖에서는 스케줄러가 쉰다. 지자체의 `totalCount`가 1,000을 넘으면 페이지 수만큼 호출이 늘어난다.

## 실제 키를 받은 첫 세션의 확인 체크리스트

키 없이 만든 코드라 응답 포맷은 포털 Swagger와 기록된 샘플로만 검증됐다. 키를 받으면 **코드를
더 쓰기 전에** 아래를 먼저 확인한다.

1. 키 인코딩: `.env`에는 포털의 **Decoding** 키를 넣는다. 클라이언트가 한 번만 인코딩한다.
   `Unauthorized`/`K30`이 오면 대부분 이중 인코딩 아니면 미승인 상태
2. **커버리지 `totalCount` 확인** — 세 지자체를 각 서비스에 대해 한 번씩:

   ```bash
   K="$REALTIME_BUS_API_KEY"   # 셸에서 직접 curl할 땐 --data-urlencode로 한 번만 인코딩
   for cd in 4159000000 4113000000 1100000000; do
     curl -sG "https://apis.data.go.kr/B551982/rte/rtm_loc_info" \
       --data-urlencode "serviceKey=$K" -d "stdgCd=$cd" -d "numOfRows=1" -d "pageNo=1" -d "type=json" \
       | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["header"]["resultCode"], d.get("body",{}).get("totalCount"))'
   done
   K="$TRAFFIC_SIGNAL_API_KEY"
   for cd in 4159000000 4113000000 1100000000; do
     curl -sG "https://apis.data.go.kr/B551982/rti/tl_drct_info" \
       --data-urlencode "serviceKey=$K" -d "stdgCd=$cd" -d "numOfRows=1" -d "pageNo=1" -d "type=json" \
       | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["header"]["resultCode"], d.get("body",{}).get("totalCount"))'
   done
   ```

   | 지자체 | 버스 `rtm_loc_info` | 신호 `tl_drct_info` | 기대 |
   |---|---|---|---|
   | 화성 `4159000000` | | | 버스 있어야 함 (주 경로 출발지) |
   | 성남 `4113000000` | | | 버스 있으면 좋음 |
   | 서울 `1100000000` | | | 신호 `K0` + totalCount > 0 (신호 커버 지역), 버스는 비어 있을 수 있음 |

   `K3`(NODATA)는 "그 지자체는 제공 안 함"이다. 오류가 아니다.
3. 버스가 세 곳 모두 `K3`/0이면 위치→ETA 경로는 쓸 수 없다. `ArrivalPredictionProvider` 구현체를
   TAGO 버스도착정보(`15098530`) 또는 경기 GBIS로 바꾼다 (키 신청 필요 — DEVELOPMENT_PLAN Phase 3 리스크).
   스키마·알고리즘은 그대로다
4. 페이지 크기: `totalCount`가 1,000을 넘는 지자체는 폴링 1회에 여러 호출이 들어간다.
   한도 계산을 다시 한다
5. 실제 응답 하나를 `src/test/resources/fixtures/klid/`에 저장해 fixture를 갱신한다
   (키·개인정보 없음 확인). 특히 `tl_drct_info`의 96개 필드 전부와 `rtm_loc_info`의 `evtCd` 값 분포
6. `oprSpd` 단위(km/h 가정)와 `gthrDt` vs `totDt` 차이(수집 지연)를 실제 값으로 확인하고
   ETA 파생 파라미터를 조정한다

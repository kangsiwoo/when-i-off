# analytics

Python 3.12 배치 모듈. **`recommend`(최적 출발 시각 계산)**(#22, 보정 테이블 연결 #46)와 그
캘리브레이션 배치인 **`derive-walking-segments`**(#44)·**`calibrate`**(#45)가 있다.

```
whenioff_analytics/
  model/       순수 통계 함수 — DB도 시계도 모른다 (분포, 신호 주기, 역산)
  io/          DB 읽기/쓰기 — 통계는 하지 않는다
  daytype.py   KST 달력: 날짜 → day_type, 시간표 벽시계 → 절대 시각
  defaults.py  콜드스타트 기본값 (보정 테이블에 쓸 만한 행이 없을 때의 마지막 fallback)
  service.py   io로 읽은 경로를 분포로 바꿔 model에 넘기는 조립 계층
  cli.py       typer 서브커맨드
```

## 준비

```bash
cd analytics
uv sync                 # 의존성은 uv.lock으로 고정
```

DB 접속은 backend와 같은 `.env` 키를 쓴다 (`WIO_DB_URL`, `WIO_DB_USER`, `WIO_DB_PASSWORD`).
`WIO_DB_URL`은 backend와 같은 JDBC 형식 그대로 둔다 — 내부에서 libpq 연결 문자열로 바꾼다.
스키마는 Flyway가 소유하므로 analytics는 DDL을 만들지도 바꾸지도 않는다.

## `recommend`

```bash
uv run wio-analytics recommend --route-id 1 --target-arrival-at 2026-09-22T09:00:00
```

- `--target-arrival-at`: ISO-8601. **타임존이 없으면 KST**로 읽는다 (`...Z`/`+09:00`도 가능)
- `--probability` / `-p`: 구간별 목표 성공확률 (기본 0.95)
- `--lookback-hours`: 후보 차량을 목표 시각에서 몇 시간 거슬러 볼지 (기본 6)
- `--dry-run`: 계산만 하고 쓰지 않는다

```
route 1 (동탄→수서 출근), p=0.95
  target arrival        2026-09-22 09:00:00 KST
  leave home at         2026-09-22 07:54:17 KST (2026-09-21T22:54:17.697471+00:00)
  catch probability     0.9500
  buffer                102s
  target date           2026-09-22
  model version         v2
  leg 1: walk, speed 1.35±0.06m/s calibrated n=12
  leg 2: vehicle 2026-09-22 08:00:00 KST of 13 candidates
      be at the stop by 2026-09-22 07:59:35 KST
      catch p=0.9500, arrive-in-time p=1.0000
      prediction error 25±30s calibrated n=12; travel 2269±56s inherited n=8
  leg 3: walk, speed 1.30±0.08m/s inherited n=20
departure_recommendations id=2 (created)
```

구간마다 쓴 입력과 그 출처(`calibrated` / `inherited` / `default`, 아래 "계산")를 찍는다. TRANSIT
구간은 고른 차량에 쓴 예측 오차(bias±σ)와 차내 시간(평균±σ)이다.

### 활성 경로 일괄 (`--all-active-routes`, #68)

```bash
uv run wio-analytics recommend --all-active-routes --only-in-window          # cron용
uv run wio-analytics recommend --all-active-routes --date 2026-10-06         # 그날 목표로 전부 (backfill)
uv run wio-analytics recommend --all-active-routes --only-in-window --now 2026-10-06T07:40 --dry-run
```

`commute_routes.is_active`인 경로마다 그 경로의 **기본 목표 도착 시각**(`default_target_arrival_time`,
KST 벽시계)을 운행일에 붙여 목표로 삼는다. 경로 하나 모드(`--route-id` + `--target-arrival-at`)와는 함께 쓸 수
없다(종료 코드 2). 경로·날짜 선택은 `model/targets.py`의 순수 함수다.

- **건너뛰기**: 목표 시각이 없는 경로(`no_target_time`), 운행일의 `day_type`(공유 공휴일 목록으로 판정, 공휴일은
  `SUNDAY_HOLIDAY`)이 경로의 `default_target_day_types`에 없는 경로(`day_type_excluded`), `--only-in-window`일 때
  지금이 창 밖인 경로(`outside_window`)
- **창**: [목표 − 120분, 목표 + 30분], 양 끝 포함. cron이 10분마다 불러도 창 밖이면 아무것도 안 한다
- **`--date`**: 운행일(YYYY-MM-DD). 생략하면 `now`의 **KST 날짜**다 — 호스트 시계가 UTC여도 07:00 KST는 그날이다.
  `--only-in-window`이고 `--date`가 없으면 전날·다음 날의 목표도 후보로 본다: 목표 00:30의 창은 전날 22:30에
  열리고 목표 23:50의 창은 다음 날 00:20에 닫힌다. `day_type`은 언제나 **목표가 속한 날짜**로 정한다
- **`--now`**: 창 판정과 기본 날짜에 쓸 "지금"(ISO-8601, 타임존 없으면 KST). 테스트·backfill용이고, 없으면 시스템 시계다
- **경로별 실패**: 후보 차량이 없거나(`NoCandidateVehiclesError`) 구간이 불완전한 경로 등은 그 경로만 `error:`로
  찍고 다음 경로로 간다. 경로마다 따로 커밋한다
- **종료 코드**: 계산을 **시도한** 경로가 있고 그 전부가 실패했을 때만 1. 전부 건너뛰었으면(창 밖 등) 0, 하나라도
  성공했으면 0이다. DB 연결 실패 같은 예상 밖 오류는 그대로 터진다

```
all active routes: 2 at 2026-10-06 07:40:00 KST (only in window), date 2026-10-06
route 1 (집 → 회사 (GTX-A)), p=0.95
  target arrival        2026-10-06 09:00:00 KST
  leave home at         2026-10-06 08:01:19 KST (2026-10-05T23:01:19.090571+00:00)
  ...
departure_recommendations id=62 (created)
route 7 (E2E 퇴근 (수서→성남)): skipped no_target_time: no default target arrival time
done: computed 1, failed 0, skipped 1
```

**멱등성**: `(commute_route_id, target_date, target_arrival_at, model_version)`당 한 행을 유지한다.
값이 그대로면 아무것도 쓰지 않아 `computed_at`까지 남는다(`unchanged`). V1 스키마에 이 조합의
UNIQUE가 없어서 `ON CONFLICT` 대신 조회 후 갱신하는 방식이다. `model_version`이 키에 들어 있어 v2가 같은
목표 시각을 다시 계산해도 v1 행은 그대로 남는다 (두 버전의 추천을 같은 trip과 비교할 수 있다).

## `derive-walking-segments`

```bash
uv run wio-analytics derive-walking-segments --from 2026-09-01 --to 2026-09-30
```

- `--from` / `--to`: `trip_date` 범위 (YYYY-MM-DD, 양 끝 포함). 생략하면 그쪽은 열려 있다(기본 전체)
- `--dry-run`: 계산만 하고 쓰지 않는다

```
trips 2 (trip_date 2030-01-01..2030-01-31)
  derived  2 (gps 1, great-circle 1)
  skipped  2 (missing_end 2)
walking_segments: created 0, updated 0, unchanged 2
```

trip마다 WALK 구간 하나당 `walking_segments` 한 행을 만든다. 시작/끝은 DATA_MODEL의 규칙 그대로
**인접 사건**에서 가져온다 — 앞 구간의 탄 시도(`CAUGHT`) 하차(없으면 `left_home_at`) → 뒤 구간의
첫 시도(`attempt_seq = 1`) 정류장 도착(없으면 `arrived_destination_at`). 도보만 있는 경로는 집 →
목적지 전체가 WALK 하나다.

- **건너뛰기**: 양 끝 중 하나가 없으면(`missing_start` / `missing_end`), 끝 ≤ 시작이면
  (`non_positive_duration`, 초 단위로 반올림해 1초 미만 포함). 하차는 `CAUGHT` 시도에서만 읽으므로
  `UNKNOWN`뿐인 구간 뒤의 WALK도 `missing_start`다
- **거리**: 그 시간 창의 그 trip `gps_traces`를 시각순으로 이은 haversine 누적. `accuracy_m > 100`
  (iOS 업로드 필터와 같은 기준, #4)인 점은 빼고, `accuracy_m`이 비어 있는 점은 남긴다. 쓸 만한
  점이 둘 미만이면 recommend와 같은 fallback(`planned_distance_m`, 없으면 구간 양 끝 좌표의
  대권거리)이다. 요약의 `gps` / `planned` / `great-circle`이 어느 쪽을 썼는지다
- **멱등성**: `(commute_trip_id, route_leg_id)` UNIQUE에 `ON CONFLICT DO UPDATE`. 값이 그대로인
  행은 건드리지 않아(`unchanged`) 다시 돌려도 결과가 같다. 이상치 속도도 저장한다 — 제외는
  `calibrate`의 몫이다
- 입력이 고쳐져 이제는 건너뛰는 구간의 **예전 행은 지운다** (`deleted`). 이번에 읽은 trip
  범위(`--from`/`--to`) 안에서만이다. 남겨 두면 `calibrate`가 이미 틀렸다고 판명된 실측을 계속 쓴다

## `calibrate`

```bash
uv run wio-analytics calibrate            # --dry-run: 계산만 하고 쓰지 않는다
```

```
walking_segments 8, boarding_attempts 5
user_walking_profile: groups 3 (per-leg 2, global 1), skipped 2 (too_slow 2)
transit_prediction_calibration: groups 3, skipped 0
transit_travel_time_calibration: groups 3, skipped 0
user_walking_profile: created 3, updated 0, unchanged 0, deleted 0
transit_prediction_calibration: created 3, updated 0, unchanged 0, deleted 0
transit_travel_time_calibration: created 3, updated 0, unchanged 0, deleted 0
```

`derive-walking-segments` 다음에 돈다. 매번 **전체 데이터**로 세 보정 테이블을 다시 계산한다
(ALGORITHM 5절). 통계는 `model/calibration.py`, DB는 `io/calibration.py`다.

| 테이블 | 샘플 | 그룹 | 윈도우 |
|---|---|---|---|
| `user_walking_profile` | `walking_segments.avg_speed_mps` (0.3 m/s 미만·3.0 m/s 초과 제외: `too_slow`/`too_fast`) | 사용자 × 구간, 사용자 전역(`route_leg_id` NULL) | `started_at` 최근 30개. 전역 행도 모든 구간에서 따로 최근 30개 |
| `transit_prediction_calibration` | 시도마다(결과 무관, 놓친 차 포함) `vehicle_actual_departure_at − vehicle_scheduled_or_predicted_at` | 노선 × 승차 정류장 × 예측 시각의 KST `day_type` × 30분 밴드 | 전체 |
| `transit_travel_time_calibration` | `CAUGHT` 시도의 `alighted_at − vehicle_actual_departure_at` (0초 이하 제외: `non_positive_duration`) | 노선 × 승차역 × 하차역 × 실제 출발 시각의 KST `day_type` × 30분 밴드 | 전체 |

- 둘 중 한 시각이 비면 그 시도는 건너뛴다 (`missing_predicted` / `missing_departure` /
  `missing_alighted`). `CAUGHT`가 아닌 시도는 원래 차내 시간의 대상이 아니라 세지 않는다
- **밴드**: KST 벽시계의 `[HH:00, HH:30)` / `[HH:30, HH+1:00)`. `TIME`은 24:00을 담지 못해 마지막 밴드는
  `23:30:00`–`23:59:59.999999`(`LAST_BAND_END`)다. 읽는 쪽은 `time_band_of(시각)`으로 시작값을 구해
  `time_band_start`가 같은 행을 찾으면 된다 (끝값 비교 불필요). `day_type`은 KST **날짜**로 정한다 —
  UTC 23:50은 KST 다음 날 08:50이다
- **평균**: 샘플의 단순 평균. 도보는 구간 속도들의 평균이다 (거리 가중 아님)
- **표준편차**: 샘플 2개 이상이면 표본표준편차(n − 1), 1개면 콜드스타트 기본값(도보 0.15 m/s,
  예측 오차 90초, 차내 시간 평균의 15%). 결과는 하한 아래로 내려가지 않는다 — 도보 0.05 m/s, 예측
  오차·차내 시간 15초. 같은 값 두 개가 σ = 0을 만들면 분위수가 한 점으로 무너지기 때문이고, geofence
  시각은 그보다 정밀하지 않다
- **INT 열**(`bias_sec`, `mean_sec`, `stddev_sec`)은 0에서 먼 쪽으로 반올림한다 (-12.5 → -13)
- 샘플이 1개인 그룹도 저장한다. "5개 미만이면 상위 그룹 상속"은 읽는 쪽(`recommend`, 아래 "계산")의 몫이다
- **멱등성**: 각 테이블의 UNIQUE 키에 `ON CONFLICT DO UPDATE`, 값이 같으면 건드리지 않는다
  (`unchanged`, `updated_at`도 그대로). 이번 결과에 없는 키의 행은 지운다(`deleted`) — 입력이 사라지거나
  고쳐진 그룹, 모든 실측이 이상치가 된 사용자의 프로필도 포함한다. `user_walking_profile`의
  `(user_id, route_leg_id)`는 `UNIQUE NULLS NOT DISTINCT`라 전역 행도 같은 `ON CONFLICT`로 잡힌다
  (PostgreSQL 15+)

## 컨테이너와 스케줄

배치는 상주 서비스가 아니라서 compose의 `analytics` 서비스는 `profiles: [batch]`로 두었다(`up`으로는 뜨지
않는다). 필요할 때 한 번씩 돌린다.

```bash
docker compose --profile batch build analytics
docker compose run --rm analytics derive-walking-segments
docker compose run --rm analytics calibrate
docker compose run --rm analytics recommend --route-id 1 --target-arrival-at 2026-10-05T09:00:00
docker compose run --rm analytics recommend --all-active-routes --only-in-window
```

DB는 compose 안에서 서비스 이름(`postgres`)으로 붙는다. 이미지는 `uv.lock` 그대로 개발 의존성 없이
설치하고, 비루트 사용자로 돈다.

정기 실행은 호스트 crontab으로 한다: [`cron.example`](cron.example).
- 새벽 03:00 `derive-walking-segments` → `calibrate` (순서가 중요하다 — calibrate는 파생된 도보 구간을 읽는다)
- 하루 종일 10분마다 `recommend --all-active-routes --only-in-window` 한 줄. 경로마다 기본 목표 시각의
  −120분 ~ +30분 창 안일 때만 다시 계산한다 (실시간 예측이 바뀌므로). 경로를 더하거나 목표 시각을 바꿔도 crontab은
  그대로다 — 목표 시각·대상 day_type은 desktop의 경로 화면(또는 `PATCH /commute-routes/{id}`)에서 정한다
- 시각은 KST. `CRON_TZ`는 "언제 돌지"만 바꾸고, 운행일·창은 recommend가 KST로 판정한다

## 계산

`docs/ALGORITHM.md` 2·3절 그대로다. `MODEL_VERSION`은 **v2** — v1(콜드스타트 기본값만)에서 보정
테이블 조회가 붙었다(#46). 테이블이 비어 있으면 v1과 숫자가 똑같다.

| 입력 | 조회 순서 (앞 단계의 샘플이 5개 미만이면 다음으로) |
|---|---|
| 도보 속도 | `user_walking_profile`의 그 구간 행 → 사용자 전역 행(`route_leg_id` NULL) → 1.2 m/s ± 0.15 |
| 도착예측 오차 | `transit_prediction_calibration`의 (노선, 승차 정류장, day_type, 밴드) → 같은 노선·정류장의 모든 행을 합친 값 → bias 0, σ 90초 |
| 차내 이동시간 | `transit_travel_time_calibration`의 (노선, 승차역, 하차역, day_type, 밴드) → 같은 노선·역 쌍의 모든 행을 합친 값 → `route_legs.planned_travel_sec`, σ = 평균의 15% |
| 신호 대기 | `traffic_signal_cycles`, 행이 없으면 C=120초 / R=90초 (`PUBLIC_API` 행이 쌓이면 그 값) |
| 후보 차량 | `transit_schedules` (나중에 실시간 예측 `transit_arrival_observations`) |
| 도보 거리 | `planned_distance_m`, 없으면 구간 양 끝 좌표의 대권거리 (`walking_segments` 실측 거리는 아직 안 씀) |

- **출처**: 쓴 값마다 `calibrated`(가장 좁은 키의 행), `inherited`(도보는 전역 행, 예측 오차·차내 시간은
  합친 값), `default`(`defaults.py`)를 붙여 CLI가 찍는다. 문턱은 `model/lookup.py`의
  `MIN_CALIBRATION_SAMPLES = 5`
- **후보 차량마다 따로 고른다**: 같은 구간에서도 차마다 KST `day_type`·30분 밴드가 다르다(자정을 넘는
  창이면 날짜도). 그래서 보정 행은 구간마다 한 번 (노선, 정류장[, 하차역])의 모든 행을 읽고
  (`io/calibration.py`), 차마다 메모리에서 고른다(`model/lookup.py`, 순수 함수). 키는 calibrate가 그룹을
  만든 기준과 같다 — 예측 오차는 **시간표(예측) 시각**, 차내 시간은 **출발 시각**의 밴드인데 출발은 아직
  모르므로 그 기댓값(예측 + 고른 bias)을 쓴다
- **합치기(pool)**: 그룹별 (n, 평균, σ)를 "모든 샘플을 한데 모아 다시 계산한 값"으로 합친다. 평균은 n 가중,
  분산은 (그룹 안 제곱합 Σ(nᵢ−1)σᵢ² + 그룹 사이 제곱합 Σnᵢ(평균ᵢ−전체 평균)²) / (N−1) — σ들의 평균이
  아니다. 샘플 1개 그룹은 그룹 안 항이 0이라 그 행에 넣어 둔 콜드스타트 σ가 섞이지 않는다. 합친 N도
  5 미만이면 기본값이다. 저장값이 정수 초로 반올림돼 있어 합친 값은 원 샘플로 계산한 것과 1초 안팎 다를 수 있다
- **σ 하한**: 고른 σ는 calibrate와 같은 하한(도보 0.05 m/s, 예측 오차·차내 시간 15초) 아래로 내리지 않는다.
  calibrate가 쓴 행은 이미 지키지만, 하한 근처 그룹들을 합치면 그 아래로 떨어질 수 있다
- 샘플이 쌓여 σ가 좁아지면 분위수 − 평균이 줄어 `buffer_seconds`가 줄고 출발이 늦어진다
  (`tests/test_lookup.py`의 마지막 테스트가 실측 → calibrate → recommend로 이를 잡는다, #6 완료 기준)

역산에서 가장 헷갈리는 두 지점:

- **분위수 방향**: 하차는 `Q_alight(p) ≤ needed_at`(차가 늦게 올 경우 대비), 승차역 도착 목표는
  `Q_board(1 − p)`(차가 일찍 올 경우 대비)다. `tests/test_recommend.py`의 앞 두 테스트가 이
  방향을 각각 잡는다.
- **시간표는 KST 벽시계**: `transit_schedules.scheduled_time`에 운행일을 붙여야 절대 시각이 된다.
  후보 창이 자정을 넘으면 날짜마다 `day_type`을 다시 판정한다 (금→토, 일→월, 공휴일 전날).
- **방향은 필수**: 한 정류장에는 상·하행이 같이 선다. `load_scheduled_departures()`의
  `direction_code`는 기본값 없는 필수 인자이고, 값은 `resolve_leg_direction()`이
  `transit_line_stops`의 승차→하차 `seq_no` 순서로 정한다 (backend `LegDirectionResolver`와 같은 규칙).
  그 테이블에 승차역 행이 없으면 방향을 모르므로 `IncompleteLegError`로 멈춘다 — 반대 방향 차를
  후보에 섞는 것보다 낫다.

문서와 다르게 한(또는 문서가 정하지 않은) 것:

- `catch_probability`는 선택된 TRANSIT 구간마다 (그 차를 탈 확률 × 제때 내릴 확률)을 곱한 값이다.
  두 사건을 독립으로 보는 근사다 (실제로는 양의 상관이 있어 약간 보수적으로 나온다).
- 신호 주기의 시간대(`time_band_*`)는 **목표 도착 시각**의 시간대로 한 번에 고른다. 그 횡단보도에
  언제 서는지는 역산이 끝나야 알 수 있는데, 통근 한 번은 대체로 시간대 하나에 들어가고 어긋나도
  기대 대기 수십 초 수준의 차이다.
- 실시간 신호 상태(ALGORITHM 2.1(b))는 `traffic_signal_states`가 비어 있어 쓰지 않는다. 들어올
  자리는 `service._crossing_wait` 한 곳이다.

## 공휴일 목록

`day_type` 판정에 필요한 공휴일 목록은 backend의
`backend/src/main/resources/calendar/kr-holidays.txt`가 **원본**이고, analytics는
`src/whenioff_analytics/resources/calendar/kr-holidays.txt`에 같은 내용을 복사해 갖는다.
backend 리소스 트리를 런타임에 읽으면 두 모듈 중 하나만 패키징하는 순간 깨지기 때문이다.
복사본이 원본과 어긋나면 `tests/test_holiday_list.py`가 깨지고(모노레포 체크아웃에서만 실행),
CI는 backend 쪽 파일이 바뀌어도 analytics 워크플로를 돌린다. 매년 말 목록을 갱신할 때는 backend
파일을 고치고 이 파일로 복사하면 된다.

## 검사

```bash
uv run ruff check . && uv run ruff format --check .
uv run mypy
uv run pytest -q
```

통계 로직은 전부 순수 함수라 DB 없이 합성 데이터로 테스트한다 (도보 구간 파생은
`tests/test_walking_segments.py`, 보정 테이블은 `tests/test_calibration.py`, recommend의
보정값 조회는 `tests/test_lookup.py`, 일괄 추천의 경로·날짜·창 선택은 `tests/test_targets.py`,
그 CLI의 인자 규칙·경로별 실패·종료 코드는 가짜 연결로 `tests/test_recommend_cli.py`). DB가 필요한 것은 `io` 계층뿐이고,
시간표 전개와 방향 판정은 가짜 커서로 테스트한다
(`tests/test_schedule_window.py`, `tests/test_leg_direction.py`).

# analytics

Python 3.12 배치 모듈. 지금 있는 것은 **`recommend`(최적 출발 시각 계산) 콜드스타트 버전**(#22)과
그 캘리브레이션 배치인 **`derive-walking-segments`**(#44)·**`calibrate`**(#45)다. recommend가 보정
테이블을 읽는 연결은 #46에 남아 있다.

```
whenioff_analytics/
  model/       순수 통계 함수 — DB도 시계도 모른다 (분포, 신호 주기, 역산)
  io/          DB 읽기/쓰기 — 통계는 하지 않는다
  daytype.py   KST 달력: 날짜 → day_type, 시간표 벽시계 → 절대 시각
  defaults.py  콜드스타트 기본값 (캘리브레이션 테이블이 비어 있는 동안 쓰는 prior)
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
  leave home at         2026-09-22 08:00:24 KST (2026-09-21T23:00:24.577354+00:00)
  catch probability     0.9500
  buffer                347s
  target date           2026-09-22
  model version         v1
  leg 2: vehicle 2026-09-22 08:14:00 KST of 13 candidates
      be at the stop by 2026-09-22 08:11:31 KST
      catch p=0.9500, arrive-in-time p=1.0000
departure_recommendations id=1 (created)
```

**멱등성**: `(commute_route_id, target_date, target_arrival_at, model_version)`당 한 행을 유지한다.
값이 그대로면 아무것도 쓰지 않아 `computed_at`까지 남는다(`unchanged`). V1 스키마에 이 조합의
UNIQUE가 없어서 `ON CONFLICT` 대신 조회 후 갱신하는 방식이다.

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
- 샘플이 1개인 그룹도 저장한다. "5개 미만이면 상위 그룹 상속"은 읽는 쪽(#46)의 몫이다
- **멱등성**: 각 테이블의 UNIQUE 키에 `ON CONFLICT DO UPDATE`, 값이 같으면 건드리지 않는다
  (`unchanged`, `updated_at`도 그대로). 이번 결과에 없는 키의 행은 지운다(`deleted`) — 입력이 사라지거나
  고쳐진 그룹, 모든 실측이 이상치가 된 사용자의 프로필도 포함한다. `user_walking_profile`의
  `(user_id, route_leg_id)`는 `UNIQUE NULLS NOT DISTINCT`라 전역 행도 같은 `ON CONFLICT`로 잡힌다
  (PostgreSQL 15+)

## 계산

`docs/ALGORITHM.md` 2·3절 그대로다. 실측 기록이 0건이라 5절의 캘리브레이션 테이블 대신
`defaults.py`의 콜드스타트 값을 쓴다.

| 입력 | 값 | 나중에 대신할 것 |
|---|---|---|
| 도보 속도 | 1.2 m/s ± 0.15 | `user_walking_profile` |
| 도착예측 오차 | bias 0, σ 90초 | `transit_prediction_calibration` |
| 차내 이동시간 | `route_legs.planned_travel_sec`, σ = 평균의 15% | `transit_travel_time_calibration` |
| 신호 대기 | `traffic_signal_cycles`, 행이 없으면 C=120초 / R=90초 | 같음 (`PUBLIC_API` 행이 쌓이면) |
| 후보 차량 | `transit_schedules` | 실시간 예측(`transit_arrival_observations`) |
| 도보 거리 | `planned_distance_m`, 없으면 구간 양 끝 좌표의 대권거리 | `walking_segments` 실측 |

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

v1에서 문서와 다르게 한(또는 문서가 정하지 않은) 것:

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
`tests/test_walking_segments.py`, 보정 테이블은 `tests/test_calibration.py`). DB가 필요한 것은 `io` 계층뿐이고,
시간표 전개와 방향 판정은 가짜 커서로 테스트한다
(`tests/test_schedule_window.py`, `tests/test_leg_direction.py`).

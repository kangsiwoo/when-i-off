# analytics

Python 3.12 배치 모듈. 지금 있는 것은 **`recommend`(최적 출발 시각 계산) 콜드스타트 버전**뿐이다
(#22). `derive-walking-segments`/`calibrate`는 원재료(trip/attempt/GPS 실측)가 아직 0건이라
#6에 남아 있다.

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

통계 로직은 전부 순수 함수라 DB 없이 합성 데이터로 테스트한다. DB가 필요한 것은 `io` 계층뿐이고,
시간표 전개와 방향 판정은 가짜 커서로 테스트한다
(`tests/test_schedule_window.py`, `tests/test_leg_direction.py`).

-- when-i-off: 초기 스키마 (PostgreSQL 16, UNIQUE NULLS NOT DISTINCT 사용)
-- 상세 설명은 docs/DATA_MODEL.md 참고. docs/db/schema.sql은 이 파일의 스냅샷이다.
--
-- 시간 규약: TIMESTAMPTZ는 절대 시각(UTC), TIME 컬럼(시간표/시간대)은 KST 기준 하루 중 시각.
-- 외부 마스터 ID(external_id, crsrd_id)는 지자체(stdg_cd, 법정동 시도코드 10자리) 안에서만 유일하므로
-- UNIQUE 키에 stdg_cd를 함께 둔다.

CREATE TYPE commute_direction AS ENUM ('TO_WORK', 'TO_HOME');
CREATE TYPE transit_mode AS ENUM ('BUS', 'SUBWAY', 'GTX');
CREATE TYPE leg_type AS ENUM ('WALK', 'TRANSIT');
CREATE TYPE day_type AS ENUM ('WEEKDAY', 'SATURDAY', 'SUNDAY_HOLIDAY');
CREATE TYPE boarding_result AS ENUM ('CAUGHT', 'MISSED', 'UNKNOWN');
CREATE TYPE signal_data_source AS ENUM ('PUBLIC_API', 'USER_OBSERVED', 'DEFAULT_ASSUMPTION');

-- ============================================================
-- 사용자
-- ============================================================
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 마스터 데이터 (노선 / 정류장 / 신호등)
-- ============================================================
CREATE TABLE transit_lines (
    id                  BIGSERIAL PRIMARY KEY,
    mode                transit_mode NOT NULL,
    stdg_cd             TEXT,                       -- 법정동 시도코드 10자리 (KLID stdgCd)
    external_id         TEXT,                       -- KLID rteId (수동 등록이면 NULL 가능)
    agency              TEXT,
    name                TEXT NOT NULL,
    has_realtime_api    BOOLEAN NOT NULL DEFAULT true,  -- false면 정적 시간표만 사용 (예: GTX)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mode, stdg_cd, external_id)
);

CREATE TABLE transit_stops (
    id              BIGSERIAL PRIMARY KEY,
    mode            transit_mode NOT NULL,
    stdg_cd         TEXT,
    external_id     TEXT,                           -- KLID bstaId
    name            TEXT NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mode, stdg_cd, external_id)
);

CREATE INDEX idx_transit_stops_latlng ON transit_stops(lat, lng);

-- 노선의 방향별 정류장 순서 (KLID ps_info). ETA 계산 시 차량 위치를 이 폴리라인에 투영한다.
CREATE TABLE transit_line_stops (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id             BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    direction_code      TEXT NOT NULL,              -- KLID drcGbnCd (상/하행)
    seq_no              INT NOT NULL,               -- KLID bstaSn
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transit_line_id, direction_code, seq_no)
);

CREATE INDEX idx_transit_line_stops_stop ON transit_line_stops(stop_id);

-- 한 행 = 교차로(intersection) 하나. 횡단보도 하나가 아니다. 어느 방향의 보행 신호를 건너는지는
-- route_leg_signal_crossings.approach_dir/signal_kind가 정한다.
CREATE TABLE traffic_signals (
    id              BIGSERIAL PRIMARY KEY,
    stdg_cd         TEXT,
    crsrd_id        TEXT,                           -- KLID crsrdId (교차로 ID)
    name            TEXT,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stdg_cd, crsrd_id)
);

CREATE INDEX idx_traffic_signals_latlng ON traffic_signals(lat, lng);

-- ============================================================
-- 출퇴근 경로 / 구간
-- ============================================================
CREATE TABLE commute_routes (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    direction       commute_direction NOT NULL,
    origin_lat      DOUBLE PRECISION NOT NULL,
    origin_lng      DOUBLE PRECISION NOT NULL,
    destination_lat DOUBLE PRECISION NOT NULL,
    destination_lng DOUBLE PRECISION NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_commute_routes_user ON commute_routes(user_id);

CREATE TABLE route_legs (
    id                  BIGSERIAL PRIMARY KEY,
    commute_route_id    BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    seq_order           INT NOT NULL,
    leg_type            leg_type NOT NULL,

    -- WALK 전용
    start_lat           DOUBLE PRECISION,
    start_lng           DOUBLE PRECISION,
    end_lat             DOUBLE PRECISION,
    end_lng             DOUBLE PRECISION,
    planned_distance_m  DOUBLE PRECISION,

    -- TRANSIT 전용 (수단 종류는 transit_lines.mode로 결정)
    transit_line_id     BIGINT REFERENCES transit_lines(id),
    board_stop_id       BIGINT REFERENCES transit_stops(id),
    alight_stop_id      BIGINT REFERENCES transit_stops(id),
    planned_travel_sec  INT,                        -- 차내 이동시간 초기값 (보정 샘플 없을 때 prior)

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (commute_route_id, seq_order),
    CONSTRAINT chk_leg_fields CHECK (
        (leg_type = 'WALK'
            AND transit_line_id IS NULL AND board_stop_id IS NULL AND alight_stop_id IS NULL
            AND start_lat IS NOT NULL AND start_lng IS NOT NULL
            AND end_lat IS NOT NULL AND end_lng IS NOT NULL)
        OR (leg_type = 'TRANSIT'
            AND transit_line_id IS NOT NULL AND board_stop_id IS NOT NULL
            AND alight_stop_id IS NOT NULL AND planned_travel_sec IS NOT NULL)
    )
);

CREATE INDEX idx_route_legs_route ON route_legs(commute_route_id);

-- approach_dir/signal_kind는 KLID tl_drct_info 필드명 접두어({dir}{kind}sg...)와 같은 코드를 쓴다.
CREATE TABLE route_leg_signal_crossings (
    id                  BIGSERIAL PRIMARY KEY,
    route_leg_id        BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,
    traffic_signal_id   BIGINT NOT NULL REFERENCES traffic_signals(id),
    seq_order           INT NOT NULL,
    approach_dir        TEXT NOT NULL
        CHECK (approach_dir IN ('nt', 'et', 'st', 'wt', 'ne', 'se', 'sw', 'nw')),
    signal_kind         TEXT NOT NULL DEFAULT 'Pd'
        CHECK (signal_kind IN ('Bs', 'Bc', 'Lt', 'Pd', 'St', 'Ut')),
    UNIQUE (route_leg_id, seq_order)
);

-- ============================================================
-- 외부 교통 데이터 (동기화 대상)
-- ============================================================
CREATE TABLE transit_schedules (
    id              BIGSERIAL PRIMARY KEY,
    transit_line_id BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id         BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    day_type        day_type NOT NULL,
    direction_code  TEXT NOT NULL,              -- transit_line_stops와 같은 어휘 (KLID drcGbnCd, GTX는 UP/DN)
    scheduled_time  TIME NOT NULL,              -- KST 하루 중 시각 (반복 시간표)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transit_schedules_lookup
    ON transit_schedules(transit_line_id, stop_id, day_type, direction_code, scheduled_time);

-- KLID rtm_loc_info 원본 차량 위치. ETA는 이 위치를 transit_line_stops 폴리라인에 투영해 계산한다.
CREATE TABLE bus_position_observations (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    vehicle_no          TEXT NOT NULL,
    observed_at         TIMESTAMPTZ NOT NULL,   -- gthrDt(없으면 totDt), KST → UTC 변환
    lat                 DOUBLE PRECISION NOT NULL,
    lng                 DOUBLE PRECISION NOT NULL,
    speed_kmh           DOUBLE PRECISION,
    heading_deg         DOUBLE PRECISION,
    receive_type        TEXT,                   -- GNSS / GPS
    raw                 JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bus_position_obs_line_time
    ON bus_position_observations(transit_line_id, observed_at);

CREATE TABLE transit_arrival_observations (
    id                      BIGSERIAL PRIMARY KEY,
    transit_line_id         BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id                 BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    observed_at             TIMESTAMPTZ NOT NULL,   -- 이 예측을 계산/조회한 시각
    predicted_arrival_at    TIMESTAMPTZ NOT NULL,   -- 그 시점의 도착 예정 시각
    source                  TEXT NOT NULL,          -- 예: 'KLID_RTM_LOC_ETA'
    vehicle_no              TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_arrival_obs_lookup
    ON transit_arrival_observations(transit_line_id, stop_id, observed_at);

-- 신호 주기 모델. 실시간 신호(traffic_signal_states)가 없는 지역의 fallback.
CREATE TABLE traffic_signal_cycles (
    id                  BIGSERIAL PRIMARY KEY,
    traffic_signal_id   BIGINT NOT NULL REFERENCES traffic_signals(id) ON DELETE CASCADE,
    day_type            day_type NOT NULL,
    time_band_start     TIME NOT NULL,   -- 이 주기가 적용되는 시간대 (KST, 자정을 넘지 않는다고 가정)
    time_band_end       TIME NOT NULL,
    red_duration_sec    INT NOT NULL,
    cycle_duration_sec  INT NOT NULL,
    source              signal_data_source NOT NULL DEFAULT 'DEFAULT_ASSUMPTION',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (red_duration_sec > 0 AND red_duration_sec < cycle_duration_sec)
);

CREATE INDEX idx_signal_cycles_lookup ON traffic_signal_cycles(traffic_signal_id, day_type);

-- KLID tl_drct_info 실시간 신호 상태. 교차로 × 접근방향 × 신호종류 × 수집시각당 1행.
-- status는 SAE J2735 MovementPhaseState 문자열을 받은 그대로 저장한다 (enum 제한 없음).
-- remaining_ds는 데시초(0.1초). 원본이 36001(알 수 없음)이거나 비어 있으면 NULL.
CREATE TABLE traffic_signal_states (
    id                  BIGSERIAL PRIMARY KEY,
    traffic_signal_id   BIGINT NOT NULL REFERENCES traffic_signals(id) ON DELETE CASCADE,
    observed_at         TIMESTAMPTZ NOT NULL,   -- totDt, KST → UTC 변환
    approach_dir        TEXT NOT NULL
        CHECK (approach_dir IN ('nt', 'et', 'st', 'wt', 'ne', 'se', 'sw', 'nw')),
    signal_kind         TEXT NOT NULL
        CHECK (signal_kind IN ('Bs', 'Bc', 'Lt', 'Pd', 'St', 'Ut')),
    status              TEXT NOT NULL,
    remaining_ds        INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (traffic_signal_id, observed_at, approach_dir, signal_kind)
);

CREATE INDEX idx_signal_states_lookup
    ON traffic_signal_states(traffic_signal_id, observed_at DESC);

-- ============================================================
-- 실측 기록 (핵심)
-- ============================================================
-- 하루의 출근(또는 퇴근) 1회 = commute_trip 1건. 그 안에서 TRANSIT 구간마다 boarding_attempt 1건.
CREATE TABLE commute_trips (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commute_route_id        BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    trip_date               DATE NOT NULL,
    left_home_at            TIMESTAMPTZ,            -- 집 geofence 이탈 또는 사용자 입력
    arrived_destination_at  TIMESTAMPTZ,            -- 목적지 geofence 진입
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_commute_trips_user_date ON commute_trips(user_id, trip_date);
CREATE INDEX idx_commute_trips_route_date ON commute_trips(commute_route_id, trip_date);

CREATE TABLE boarding_attempts (
    id                                  BIGSERIAL PRIMARY KEY,
    commute_trip_id                     BIGINT NOT NULL REFERENCES commute_trips(id) ON DELETE CASCADE,
    route_leg_id                        BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,

    arrived_at_stop_at                  TIMESTAMPTZ,    -- 승차 정류장/역 도착 (geofence 진입)
    vehicle_scheduled_or_predicted_at   TIMESTAMPTZ,    -- 그 순간 시스템이 알던 예정 시각 (스냅샷)
    vehicle_actual_departure_at         TIMESTAMPTZ,    -- 실제 그 차가 떠난 시각 (탑승 시각 or 목격 시각)
    alighted_at                         TIMESTAMPTZ,    -- 하차 정류장/역 도착 (geofence 진입)

    result                              boarding_result NOT NULL DEFAULT 'UNKNOWN',
    notes                               TEXT,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (commute_trip_id, route_leg_id)
);

CREATE INDEX idx_boarding_attempts_leg ON boarding_attempts(route_leg_id);

-- (user_id, recorded_at) UNIQUE: 앱의 오프라인 재전송을 ON CONFLICT DO NOTHING으로 흡수하기 위함
CREATE TABLE gps_traces (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commute_trip_id BIGINT REFERENCES commute_trips(id) ON DELETE SET NULL,  -- NULL = 상시 수집분
    recorded_at     TIMESTAMPTZ NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    speed_mps       DOUBLE PRECISION,
    accuracy_m      DOUBLE PRECISION,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, recorded_at)
);

CREATE INDEX idx_gps_traces_trip ON gps_traces(commute_trip_id);

-- ============================================================
-- 파생/캘리브레이션 결과 (Analytics가 채움)
-- ============================================================
CREATE TABLE walking_segments (
    id              BIGSERIAL PRIMARY KEY,
    commute_trip_id BIGINT NOT NULL REFERENCES commute_trips(id) ON DELETE CASCADE,
    route_leg_id    BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ NOT NULL,
    duration_sec    INT NOT NULL,
    distance_m      DOUBLE PRECISION NOT NULL,
    avg_speed_mps   DOUBLE PRECISION NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (commute_trip_id, route_leg_id)
);

CREATE INDEX idx_walking_segments_leg ON walking_segments(route_leg_id, started_at);

CREATE TABLE user_walking_profile (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route_leg_id        BIGINT REFERENCES route_legs(id) ON DELETE CASCADE, -- NULL = 전역 기본값
    avg_speed_mps       DOUBLE PRECISION NOT NULL,
    stddev_speed_mps    DOUBLE PRECISION NOT NULL,
    sample_count        INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (user_id, route_leg_id)
);

-- 외부 예측(or 시간표) 대비 실제 도착의 오차: 실제 - 예측
CREATE TABLE transit_prediction_calibration (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id             BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    day_type            day_type NOT NULL,
    time_band_start     TIME NOT NULL,
    time_band_end       TIME NOT NULL,
    bias_sec            INT NOT NULL DEFAULT 0,
    stddev_sec          INT NOT NULL DEFAULT 90,      -- 콜드스타트 기본값
    sample_count        INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transit_line_id, stop_id, day_type, time_band_start, time_band_end)
);

-- 승차역 → 하차역 차내 이동시간 (알고리즘이 하차 시각을 역산할 때 필요)
CREATE TABLE transit_travel_time_calibration (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    board_stop_id       BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    alight_stop_id      BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    day_type            day_type NOT NULL,
    time_band_start     TIME NOT NULL,
    time_band_end       TIME NOT NULL,
    mean_sec            INT NOT NULL,
    stddev_sec          INT NOT NULL,
    sample_count        INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transit_line_id, board_stop_id, alight_stop_id, day_type, time_band_start, time_band_end)
);

-- ============================================================
-- 최종 추천 결과
-- ============================================================
CREATE TABLE departure_recommendations (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commute_route_id            BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    target_date                 DATE NOT NULL,
    target_arrival_at           TIMESTAMPTZ NOT NULL,
    recommended_leave_home_at   TIMESTAMPTZ NOT NULL,
    catch_probability           DOUBLE PRECISION NOT NULL,
    buffer_seconds              INT NOT NULL,
    model_version               TEXT NOT NULL,
    computed_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_departure_reco_lookup
    ON departure_recommendations(commute_route_id, target_date, computed_at DESC);

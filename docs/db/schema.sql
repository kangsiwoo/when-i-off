-- when-i-off: 초기 스키마 설계 (PostgreSQL 가정)
-- 상세 설명은 docs/DATA_MODEL.md 참고.
-- 실제 backend 구현 시 이 파일 내용을 Flyway 마이그레이션
-- (backend/src/main/resources/db/migration/V1__init_schema.sql)으로 옮겨서 사용한다.

CREATE TYPE commute_direction AS ENUM ('TO_WORK', 'TO_HOME');
CREATE TYPE transit_mode AS ENUM ('BUS', 'SUBWAY', 'GTX');
CREATE TYPE leg_type AS ENUM ('WALK', 'BUS', 'SUBWAY', 'GTX');
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

-- 노선/정류장 마스터 (transit_lines보다 먼저 선언되어야 route_legs의 FK가 걸림)
CREATE TABLE transit_lines (
    id              BIGSERIAL PRIMARY KEY,
    mode            transit_mode NOT NULL,
    external_id     TEXT,                       -- 공공데이터포털 등 외부 노선 ID
    agency          TEXT,
    name            TEXT NOT NULL,
    has_realtime_api BOOLEAN NOT NULL DEFAULT true,  -- false면 GTX처럼 정적 시간표만 사용
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mode, external_id)
);

CREATE TABLE transit_stops (
    id              BIGSERIAL PRIMARY KEY,
    mode            transit_mode NOT NULL,
    external_id     TEXT,
    name            TEXT NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mode, external_id)
);

CREATE TABLE traffic_signals (
    id              BIGSERIAL PRIMARY KEY,
    external_id     TEXT,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE route_legs (
    id                  BIGSERIAL PRIMARY KEY,
    commute_route_id    BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    seq_order           INT NOT NULL,
    leg_type            leg_type NOT NULL,

    -- WALK 전용 필드
    start_lat           DOUBLE PRECISION,
    start_lng           DOUBLE PRECISION,
    end_lat             DOUBLE PRECISION,
    end_lng             DOUBLE PRECISION,
    planned_distance_m  DOUBLE PRECISION,

    -- BUS/SUBWAY/GTX 전용 필드
    transit_line_id     BIGINT REFERENCES transit_lines(id),
    board_stop_id       BIGINT REFERENCES transit_stops(id),
    alight_stop_id      BIGINT REFERENCES transit_stops(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (commute_route_id, seq_order),
    CONSTRAINT chk_leg_fields CHECK (
        (leg_type = 'WALK' AND transit_line_id IS NULL)
        OR (leg_type <> 'WALK' AND transit_line_id IS NOT NULL
            AND board_stop_id IS NOT NULL AND alight_stop_id IS NOT NULL)
    )
);

CREATE INDEX idx_route_legs_route ON route_legs(commute_route_id);

CREATE TABLE route_leg_signal_crossings (
    id              BIGSERIAL PRIMARY KEY,
    route_leg_id    BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,
    traffic_signal_id BIGINT NOT NULL REFERENCES traffic_signals(id),
    seq_order       INT NOT NULL,
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
    scheduled_time  TIME NOT NULL,              -- 하루 중 시각 (반복 시간표)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transit_schedules_lookup
    ON transit_schedules(transit_line_id, stop_id, day_type, scheduled_time);

CREATE TABLE transit_arrival_observations (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id             BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    observed_at         TIMESTAMPTZ NOT NULL,      -- 이 예측을 조회한 시각
    predicted_arrival_at TIMESTAMPTZ NOT NULL,      -- 그 시점에 API가 알려준 도착 예정 시각
    source              TEXT NOT NULL,              -- 예: 'TAGO', 'SEOUL_OPEN_DATA'
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_arrival_obs_lookup
    ON transit_arrival_observations(transit_line_id, stop_id, observed_at);

CREATE TABLE traffic_signal_cycles (
    id                  BIGSERIAL PRIMARY KEY,
    traffic_signal_id   BIGINT NOT NULL REFERENCES traffic_signals(id) ON DELETE CASCADE,
    day_type            day_type NOT NULL,
    time_band_start     TIME NOT NULL,   -- 이 주기가 적용되는 시간대 시작
    time_band_end       TIME NOT NULL,
    red_duration_sec    INT NOT NULL,
    cycle_duration_sec  INT NOT NULL,
    source              signal_data_source NOT NULL DEFAULT 'DEFAULT_ASSUMPTION',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_signal_cycles_lookup ON traffic_signal_cycles(traffic_signal_id, day_type);

-- ============================================================
-- 실측 기록 (핵심)
-- ============================================================
CREATE TABLE boarding_attempts (
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commute_route_id                BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    route_leg_id                    BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,
    target_date                     DATE NOT NULL,

    left_home_at                    TIMESTAMPTZ,
    arrived_at_stop_at              TIMESTAMPTZ,
    vehicle_scheduled_or_predicted_at TIMESTAMPTZ,
    vehicle_actual_departure_at     TIMESTAMPTZ,

    result                          boarding_result NOT NULL DEFAULT 'UNKNOWN',
    notes                           TEXT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_boarding_attempts_user_date ON boarding_attempts(user_id, target_date);
CREATE INDEX idx_boarding_attempts_leg ON boarding_attempts(route_leg_id);

CREATE TABLE gps_traces (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    boarding_attempt_id BIGINT REFERENCES boarding_attempts(id) ON DELETE SET NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    lat                 DOUBLE PRECISION NOT NULL,
    lng                 DOUBLE PRECISION NOT NULL,
    speed_mps           DOUBLE PRECISION,
    accuracy_m          DOUBLE PRECISION,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_gps_traces_user_time ON gps_traces(user_id, recorded_at);
CREATE INDEX idx_gps_traces_attempt ON gps_traces(boarding_attempt_id);

-- ============================================================
-- 파생/캘리브레이션 결과 (Analytics가 채움)
-- ============================================================
CREATE TABLE walking_segments (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route_leg_id    BIGINT NOT NULL REFERENCES route_legs(id) ON DELETE CASCADE,
    segment_date    DATE NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ NOT NULL,
    duration_sec    INT NOT NULL,
    distance_m      DOUBLE PRECISION NOT NULL,
    avg_speed_mps   DOUBLE PRECISION NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_walking_segments_leg_date ON walking_segments(route_leg_id, segment_date);

CREATE TABLE user_walking_profile (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route_leg_id        BIGINT REFERENCES route_legs(id) ON DELETE CASCADE, -- NULL = 전역 기본값
    avg_speed_mps       DOUBLE PRECISION NOT NULL,
    stddev_speed_mps    DOUBLE PRECISION NOT NULL,
    sample_count        INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, route_leg_id)
);

CREATE TABLE transit_prediction_calibration (
    id                  BIGSERIAL PRIMARY KEY,
    transit_line_id     BIGINT NOT NULL REFERENCES transit_lines(id) ON DELETE CASCADE,
    stop_id             BIGINT NOT NULL REFERENCES transit_stops(id) ON DELETE CASCADE,
    day_type            day_type NOT NULL,
    time_band_start     TIME NOT NULL,
    time_band_end       TIME NOT NULL,
    bias_sec            INT NOT NULL DEFAULT 0,       -- 평균(실제 - 예측)
    stddev_sec          INT NOT NULL DEFAULT 90,      -- 콜드스타트 기본값
    sample_count        INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transit_line_id, stop_id, day_type, time_band_start, time_band_end)
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

-- 추천 성과 평가 (#72, #8).
--
-- "추천대로 나갔을 때 실제로 됐는가"를 model_version별로 쌓아 모델을 바꿀 때 비교한다. analytics
-- `evaluate` 배치가 (경로, 날짜, 버전)마다 그날 마지막으로 계산된 추천(#62와 같은 기준: computed_at DESC,
-- id DESC)과 그날의 trip 하나를 짝지어 한 행을 upsert한다. trip이 없는 날은 행을 만들지 않는다.
--
-- 모든 값은 파생값이라 원본(추천·trip)이 지워지면 함께 지운다(ON DELETE CASCADE). 배치를 다시 돌려도
-- 같은 결과가 나오므로 잃는 것이 없다.
CREATE TABLE recommendation_evaluations (
    id                          BIGSERIAL PRIMARY KEY,
    commute_route_id            BIGINT NOT NULL REFERENCES commute_routes(id) ON DELETE CASCADE,
    target_date                 DATE NOT NULL,              -- KST. 추천의 target_date = trip의 trip_date
    model_version               TEXT NOT NULL,
    departure_recommendation_id BIGINT NOT NULL REFERENCES departure_recommendations(id) ON DELETE CASCADE,
    commute_trip_id             BIGINT NOT NULL REFERENCES commute_trips(id) ON DELETE CASCADE,

    -- 비교한 두 쪽의 시각 (원본의 복사본 — 평가 당시 무엇과 무엇을 비교했는지 행만 보고 알 수 있게)
    target_arrival_at           TIMESTAMPTZ NOT NULL,
    recommended_leave_home_at   TIMESTAMPTZ NOT NULL,
    actual_left_home_at         TIMESTAMPTZ,
    actual_arrived_at           TIMESTAMPTZ,

    departure_diff_sec          INT,        -- 실제 출발 − 추천 출발 (+ = 늦게 나감). 실제 출발이 없으면 NULL
    arrival_diff_sec            INT,        -- 실제 도착 − 목표 도착 (+ = 늦음). 실제 도착이 없으면 NULL
    is_late                     BOOLEAN,    -- 실제 도착 > 목표 도착 (정각은 지각 아님). 도착이 없으면 NULL
    all_legs_caught             BOOLEAN NOT NULL,   -- TRANSIT 구간마다 CAUGHT 시도가 있다 (#62 allLegsCaught)
    missed_count                INT NOT NULL CHECK (missed_count >= 0),  -- MISSED 시도 수 (#62 missedCount)
    avg_stop_wait_sec           INT,        -- CAUGHT 구간의 (탄 차 실제 출발 − 첫 시도 정류장 도착) 평균
    evaluated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),     -- 값이 바뀐 때만 움직인다

    CONSTRAINT uq_recommendation_evaluations_route_date_version
        UNIQUE (commute_route_id, target_date, model_version)
);

CREATE INDEX idx_recommendation_evaluations_version
    ON recommendation_evaluations(model_version, target_date);
CREATE INDEX idx_recommendation_evaluations_recommendation
    ON recommendation_evaluations(departure_recommendation_id);
CREATE INDEX idx_recommendation_evaluations_trip
    ON recommendation_evaluations(commute_trip_id);

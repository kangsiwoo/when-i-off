-- 추천 뒤에 있는 실측 표본 수 (#86, ADR 0003 §8).
--
-- 지금까지 추천 응답에는 "이 확률이 실측 몇 건에 기대는가"가 없어서 iOS가 trip 이력에서 구간별 시도 수를
-- 세어 콜드스타트를 추정했다. analytics recommend(v2)는 고른 차량마다 예측 오차·차내 시간 입력을 보정
-- 테이블에서 고르고 그 입력의 sample_count를 이미 안다(기본값이면 0, 그 밖에는 MIN_CALIBRATION_SAMPLES = 5
-- 이상). 그중 가장 작은 값을 적재한다.
--
-- - min_transit_sample_count: TRANSIT 구간마다 고른 차량의 두 입력(예측 오차, 차내 시간) 표본 수 중 최솟값.
--   0이면 어느 입력이 콜드스타트 기본값으로 내려갔다는 뜻이다. 성공확률은 TRANSIT 구간 입력으로만 계산되므로
--   (도보는 출발 시각만 당긴다) 도보 표본은 세지 않는다
-- - NULL: 이 컬럼 이전(V8 전)에 적재된 행, 또는 TRANSIT 구간이 없는 경로. 읽는 쪽은 "모름"으로 다룬다
ALTER TABLE departure_recommendations
    ADD COLUMN min_transit_sample_count INT
        CONSTRAINT chk_departure_recommendations_min_transit_sample_count
            CHECK (min_transit_sample_count >= 0);

-- 경로별 기본 목표 도착 시각 (#68, #7).
--
-- 지금까지 recommend는 경로 id와 목표 시각을 인자로 받아 crontab에 경로마다 줄을 적었다. 경로가 자기
-- 목표 도착 시각(KST 벽시계)과 대상 day_type 집합을 갖게 해 `recommend --all-active-routes`가 활성 경로를
-- 한 번에 돈다.
--
-- - default_target_arrival_time: KST 벽시계. NULL이면 그 경로는 일괄 추천에서 빠진다
-- - default_target_day_types: 그날의 day_type(공유 공휴일 목록으로 판정)이 이 집합에 있을 때만 계산한다.
--   요일 마스크 대신 day_type을 쓰는 것은 시간표·보정 테이블과 같은 달력을 쓰기 위해서다 (공휴일 = 일요일).
--   빈 배열은 "아무 날도 아님"이라 의미가 없으므로 막는다. 끄려면 is_active를 내린다
ALTER TABLE commute_routes
    ADD COLUMN default_target_arrival_time TIME,
    ADD COLUMN default_target_day_types day_type[] NOT NULL DEFAULT '{WEEKDAY}'
        CONSTRAINT chk_commute_routes_default_target_day_types CHECK (
            cardinality(default_target_day_types) >= 1
            AND array_position(default_target_day_types, NULL) IS NULL
        );

-- 같은 경로·같은 "집 나섬" 시각의 trip은 하나뿐이다 (#37).
--
-- 지하에서 "집 나섬" 응답을 못 받은 앱이 같은 요청을 다시 보내면 trip이 두 개 생겼다.
-- commute_trips는 "같은 날 여러 번 이동"을 허용해야 해서 (경로, 날짜) 유일 제약을 걸 수 없지만,
-- 밀리초까지 같은 left_home_at을 가진 서로 다른 출근은 없다. 그래서 (경로, left_home_at)을 키로 삼아
-- 재전송을 기존 trip으로 흡수한다 (POST가 201 대신 200). 탑승 시도가 (trip, 구간)으로 upsert되는 것과
-- 같은 성격이다.
--
-- left_home_at 없이 만든 trip은 키가 없으므로 제외한다 (부분 인덱스). 서비스가 먼저 조회하지만,
-- 동시에 들어온 두 요청이 둘 다 "없음"을 보고 삽입하는 경쟁은 이 인덱스가 막는다 — 진 쪽은 유일 위반을
-- 받고 기존 행을 돌려받는다 (CommuteTripController).
--
-- 이미 중복이 있으면 이 마이그레이션은 실패한다. 어느 행을 남길지(탑승 시도가 어느 쪽에 붙었는지)는
-- 사람이 봐야 하는 문제라 여기서 지우지 않는다. 실측 기록이 0건인 지금은 해당 없다.
CREATE UNIQUE INDEX uq_commute_trips_route_left_home
    ON commute_trips (commute_route_id, left_home_at)
    WHERE left_home_at IS NOT NULL;

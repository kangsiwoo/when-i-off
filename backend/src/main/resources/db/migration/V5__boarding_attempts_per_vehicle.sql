-- 한 구간에서 차를 놓치고 다음 차를 타면 탑승 시도가 두 건이다 (#38).
--
-- 지금까지는 (trip, 구간)이 유일해서 두 번째 시도가 첫 번째를 덮어썼다. 놓친 기록이 사라질 뿐 아니라
-- "놓친 차의 예측 시각 스냅샷 + 탄 차의 실제 출발"이 한 행에 섞여 가짜 예측 오차가 학습에 들어간다.
-- 차 한 대 = 한 행으로 바꾸고, 같은 구간 안에서 몇 번째 차인지를 attempt_seq(1부터)로 구분한다.
-- 재전송 멱등성의 키도 (trip, 구간, attempt_seq)가 된다.
--
-- 기존 행은 모두 첫 시도(1)가 된다. 실측 기록이 0건인 지금은 해당 행도 없다.
ALTER TABLE boarding_attempts
    ADD COLUMN attempt_seq INT NOT NULL DEFAULT 1 CHECK (attempt_seq >= 1);

ALTER TABLE boarding_attempts
    DROP CONSTRAINT boarding_attempts_commute_trip_id_route_leg_id_key;

ALTER TABLE boarding_attempts
    ADD CONSTRAINT boarding_attempts_trip_leg_attempt_seq_key
        UNIQUE (commute_trip_id, route_leg_id, attempt_seq);

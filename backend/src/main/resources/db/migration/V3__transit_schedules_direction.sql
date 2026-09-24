-- transit_schedules에 방향을 붙인다 (#26).
--
-- 한 정류장은 거의 항상 상·하행 양쪽에 선다. (노선, 정류장, day_type)만으로는 "다음 차"가
-- 반대 방향 차를 섞어 돌려주므로, 방향을 키에 넣는다.
--
-- 새 enum을 만들지 않고 TEXT를 쓰는 이유: 방향 어휘는 이미 transit_line_stops.direction_code
-- (KLID/TAGO drcGbnCd)에 있고, LegDirectionResolver가 그 값을 그대로 돌려준다. 시간표 쪽에만
-- 다른 타입을 쓰면 둘을 비교할 때마다 변환이 필요하고, 사업자마다 다른 코드값('0'/'1',
-- 'UP'/'DN', 순환 노선 표기)을 enum으로 고정하면 노선을 추가할 때마다 마이그레이션이 생긴다.
ALTER TABLE transit_schedules ADD COLUMN direction_code TEXT;

-- 기존 행 backfill. 지금 들어 있는 것은 R__seed_gtx_a.sql이 넣은 GTX-A 종착역 2개뿐이고,
-- 종착역이라 방향이 하나로 정해진다 — 동탄(X111)은 수서 방면(UP), 수서(X108)는 동탄 방면(DN).
-- (UP = 역번호가 작아지는 쪽, DN = 커지는 쪽. R__seed_gtx_a.sql 주석 참고.)
-- 테이블이 비어 있으면 0행을 고치고 지나간다. 이 둘 말고 다른 행이 있으면 NULL로 남아
-- 아래 SET NOT NULL이 실패하는데, 방향을 추측해 넣는 것보다 낫다.
UPDATE transit_schedules ts
SET direction_code = CASE s.external_id WHEN 'X111' THEN 'UP' ELSE 'DN' END
FROM transit_lines l, transit_stops s
WHERE ts.transit_line_id = l.id
  AND ts.stop_id = s.id
  AND l.mode = 'GTX' AND l.stdg_cd = 'GTX-A' AND l.external_id = 'L09'
  AND s.mode = 'GTX' AND s.stdg_cd = 'GTX-A' AND s.external_id IN ('X111', 'X108');

ALTER TABLE transit_schedules ALTER COLUMN direction_code SET NOT NULL;

-- 조회는 (노선, 정류장, day_type, 방향)으로 좁힌 뒤 scheduled_time으로 훑는다.
-- direction_code가 동등 조건 마지막, scheduled_time이 범위/정렬 컬럼이라 이 순서여야 한다.
DROP INDEX idx_transit_schedules_lookup;
CREATE INDEX idx_transit_schedules_lookup
    ON transit_schedules(transit_line_id, stop_id, day_type, direction_code, scheduled_time);

-- GTX-A 수서~동탄 노선·역·평일 시간표 시드 (#20)
--
-- 출처: GTX-A 공식 사이트의 열차시간표 API (www.gtx-a.com/trainTimeTable.do), 2026-09-20 수집.
-- 좌표는 위키백과. 역 코드(X111/X108)와 노선 코드(L09)는 공식 사이트가 쓰는 값 그대로다.
--
-- 평일만 넣는 이유: dayTypeCode=1(평일)과 3(휴일) 응답이 9개 역 전부 완전히 동일하게 온다.
-- 출퇴근 시간대가 조밀한 평일 패턴이 그대로라 휴일 시간표가 같은 게 아니라 API가 파라미터를
-- 무시하는 것으로 보여, 틀린 시각을 넣느니 비워 둔다.
--
-- 종착역만 넣는 이유: transit_schedules에 방향 컬럼이 없어 (노선, 정류장, day_type)으로는
-- 상·하행이 구분되지 않는다. 중간역을 넣으면 "다음 차" 조회가 양방향을 섞어 돌려준다.
-- 동탄은 상행, 수서는 하행뿐이라 모호함이 없다.
--
-- R__ 반복 마이그레이션이라 체크섬이 바뀔 때마다 다시 적용된다. 여러 번 돌려도 결과가 같게
-- 노선·역은 upsert, 시간표는 (노선, 정류장, day_type) 단위로 지우고 다시 넣는다.

INSERT INTO transit_lines (mode, stdg_cd, external_id, name, has_realtime_api)
VALUES ('GTX', 'GTX-A', 'L09', 'GTX-A (수서~동탄)', false)
ON CONFLICT (mode, stdg_cd, external_id)
DO UPDATE SET name = EXCLUDED.name, has_realtime_api = EXCLUDED.has_realtime_api;

INSERT INTO transit_stops (mode, stdg_cd, external_id, name, lat, lng)
VALUES
    ('GTX', 'GTX-A', 'X111', '동탄', 37.201167, 127.095111),
    ('GTX', 'GTX-A', 'X108', '수서', 37.48694, 127.10194)
ON CONFLICT (mode, stdg_cd, external_id)
DO UPDATE SET name = EXCLUDED.name, lat = EXCLUDED.lat, lng = EXCLUDED.lng;

DELETE FROM transit_schedules ts
USING transit_lines l, transit_stops s
WHERE ts.transit_line_id = l.id
  AND ts.stop_id = s.id
  AND ts.day_type = 'WEEKDAY'
  AND l.mode = 'GTX' AND l.stdg_cd = 'GTX-A' AND l.external_id = 'L09'
  AND s.mode = 'GTX' AND s.stdg_cd = 'GTX-A' AND s.external_id IN ('X111', 'X108');

-- 동탄(X111) 수서 방면 평일 60편
INSERT INTO transit_schedules (transit_line_id, stop_id, day_type, scheduled_time)
SELECT l.id, s.id, 'WEEKDAY', t.at::time
FROM transit_lines l
JOIN transit_stops s
  ON s.mode = 'GTX' AND s.stdg_cd = 'GTX-A' AND s.external_id = 'X111'
CROSS JOIN (VALUES
        ('00:06'),
        ('00:27'),
        ('05:30'),
        ('05:49'),
        ('06:09'),
        ('06:26'),
        ('06:43'),
        ('07:02'),
        ('07:22'),
        ('07:36'),
        ('07:55'),
        ('08:14'),
        ('08:28'),
        ('08:44'),
        ('09:00'),
        ('09:18'),
        ('09:30'),
        ('09:50'),
        ('10:14'),
        ('10:30'),
        ('10:50'),
        ('11:12'),
        ('11:36'),
        ('12:05'),
        ('12:32'),
        ('12:55'),
        ('13:12'),
        ('13:22'),
        ('13:47'),
        ('14:09'),
        ('14:24'),
        ('14:43'),
        ('15:10'),
        ('15:28'),
        ('15:38'),
        ('15:58'),
        ('16:10'),
        ('16:33'),
        ('16:49'),
        ('17:07'),
        ('17:26'),
        ('17:44'),
        ('18:06'),
        ('18:19'),
        ('18:43'),
        ('19:00'),
        ('19:15'),
        ('19:29'),
        ('19:46'),
        ('20:00'),
        ('20:35'),
        ('20:47'),
        ('21:00'),
        ('21:17'),
        ('21:31'),
        ('21:44'),
        ('22:15'),
        ('22:37'),
        ('23:12'),
        ('23:39')
) AS t(at)
WHERE l.mode = 'GTX' AND l.stdg_cd = 'GTX-A' AND l.external_id = 'L09';

-- 수서(X108) 동탄 방면 평일 60편
INSERT INTO transit_schedules (transit_line_id, stop_id, day_type, scheduled_time)
SELECT l.id, s.id, 'WEEKDAY', t.at::time
FROM transit_lines l
JOIN transit_stops s
  ON s.mode = 'GTX' AND s.stdg_cd = 'GTX-A' AND s.external_id = 'X108'
CROSS JOIN (VALUES
        ('00:15'),
        ('00:39'),
        ('05:45'),
        ('06:04'),
        ('06:13'),
        ('06:21'),
        ('06:44'),
        ('07:09'),
        ('07:29'),
        ('07:46'),
        ('08:09'),
        ('08:21'),
        ('08:35'),
        ('08:49'),
        ('09:10'),
        ('09:27'),
        ('09:47'),
        ('10:04'),
        ('10:10'),
        ('10:36'),
        ('11:04'),
        ('11:30'),
        ('11:50'),
        ('12:09'),
        ('12:32'),
        ('12:41'),
        ('13:14'),
        ('13:34'),
        ('13:46'),
        ('14:00'),
        ('14:20'),
        ('14:45'),
        ('15:10'),
        ('15:25'),
        ('15:43'),
        ('16:02'),
        ('16:25'),
        ('16:43'),
        ('16:59'),
        ('17:15'),
        ('17:34'),
        ('17:47'),
        ('18:10'),
        ('18:28'),
        ('18:42'),
        ('18:51'),
        ('19:28'),
        ('19:46'),
        ('20:04'),
        ('20:19'),
        ('20:35'),
        ('20:50'),
        ('21:12'),
        ('21:32'),
        ('21:50'),
        ('22:08'),
        ('22:30'),
        ('23:00'),
        ('23:25'),
        ('23:50')
) AS t(at)
WHERE l.mode = 'GTX' AND l.stdg_cd = 'GTX-A' AND l.external_id = 'L09';

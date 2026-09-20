-- 1인 사용 단계: 모든 API 요청은 X-Api-Token으로 인증되며 이 사용자(id=1)로 귀속된다.
INSERT INTO users (id, email, display_name)
VALUES (1, 'owner@when-i-off.local', 'owner')
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST((SELECT max(id) FROM users), 1));

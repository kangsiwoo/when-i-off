-- 통합 테스트는 Testcontainers 없이 실제 PostgreSQL에 붙고(CONVENTIONS.md), Flyway clean 후
-- 마이그레이션을 다시 적용하므로 개발용 DB와 반드시 분리돼야 한다. 그래서 같은 컨테이너에
-- 테스트 전용 DB를 하나 더 만들어 둔다.
--
-- 이 스크립트는 데이터 볼륨이 비어 있을 때(최초 기동) 한 번만 실행된다. 이미 볼륨이 있는
-- 환경이라면 아래를 직접 실행하거나 볼륨을 지우고 다시 올려야 한다:
--   docker compose exec postgres psql -U wio -d when_i_off -c "CREATE DATABASE when_i_off_test OWNER wio;"
CREATE DATABASE when_i_off_test OWNER wio;

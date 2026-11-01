#!/bin/sh
# 통합 테스트는 Testcontainers 없이 실제 PostgreSQL에 붙고(CONVENTIONS.md), 테스트마다 테이블을
# TRUNCATE하므로 개발용 DB와 반드시 분리돼야 한다(test 프로필 기본 WIO_TEST_DB_URL이 이 DB). 그래서 같은 컨테이너에
# 테스트 전용 DB를 하나 더 만들어 둔다. 소유자는 compose의 WIO_DB_USER(= POSTGRES_USER)를 따른다.
#
# 이 스크립트는 데이터 볼륨이 비어 있을 때(최초 기동) 한 번만 실행된다. 이미 볼륨이 있는
# 환경이라면 아래를 직접 실행하거나 볼륨을 지우고 다시 올려야 한다:
#   docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d when_i_off -c "CREATE DATABASE when_i_off_test OWNER \"$POSTGRES_USER\";"'
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE DATABASE when_i_off_test OWNER "$POSTGRES_USER";
SQL

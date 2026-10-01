#!/usr/bin/env bash
# when-i-off PostgreSQL 일일 백업 (#74). pg_dump custom 포맷(-Fc) 한 파일 + 보관 개수 회전.
#
# 모든 값은 환경변수로 받는다. 비밀번호는 이 파일·cron에 쓰지 않는다:
#   - compose 모드(기본): 컨테이너 안에서 로컬 소켓으로 붙는다(postgres 이미지 기본 trust) — 비밀번호 불필요
#   - host 모드: 호스트의 pg_dump가 libpq 표준 변수(PGHOST/PGPORT/PGPASSWORD) 또는 ~/.pgpass를 읽는다
#
#   WIO_BACKUP_DIR           백업 디렉터리 (기본 /var/backups/when-i-off)
#   WIO_BACKUP_KEEP          남길 개수, 오래된 것부터 지운다 (기본 14)
#   WIO_BACKUP_MODE          compose | host (기본 compose)
#   WIO_BACKUP_COMPOSE_FILE  compose 파일 (기본: 이 스크립트 기준 레포 루트의 docker-compose.yml)
#   WIO_BACKUP_SERVICE       compose의 DB 서비스 이름 (기본 postgres)
#   WIO_BACKUP_DB            DB 이름 (기본 when_i_off)
#   WIO_BACKUP_DB_USER       DB 사용자 (기본 wio)
#
# 복구: pg_restore --clean --if-exists -d when_i_off <파일>  (backend/README.md "백업")
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backup_dir="${WIO_BACKUP_DIR:-/var/backups/when-i-off}"
keep="${WIO_BACKUP_KEEP:-14}"
mode="${WIO_BACKUP_MODE:-compose}"
compose_file="${WIO_BACKUP_COMPOSE_FILE:-$script_dir/../../docker-compose.yml}"
service="${WIO_BACKUP_SERVICE:-postgres}"
db="${WIO_BACKUP_DB:-when_i_off}"
db_user="${WIO_BACKUP_DB_USER:-wio}"

log() { printf '%s pg_dump.sh: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[[ "$keep" =~ ^[1-9][0-9]*$ ]] || die "WIO_BACKUP_KEEP must be a positive integer (got '$keep')"
[[ "$mode" == compose || "$mode" == host ]] || die "WIO_BACKUP_MODE must be compose or host (got '$mode')"

umask 077 # 덤프에는 위치 기록이 들어 있다. 소유자만 읽게 한다
mkdir -p "$backup_dir"

# 이름이 시각순으로 정렬되도록 UTC 타임스탬프를 쓴다. 회전은 이 정렬에 기댄다.
name="${db}-$(date -u +%Y%m%dT%H%M%SZ).dump"
target="$backup_dir/$name"
tmp="$target.partial"
trap 'rm -f "$tmp"' EXIT

log "dumping $db ($mode) -> $target"
case "$mode" in
  compose)
    docker compose -f "$compose_file" exec -T "$service" \
      pg_dump -U "$db_user" -d "$db" -Fc --no-owner >"$tmp"
    ;;
  host)
    pg_dump -U "$db_user" -d "$db" -Fc --no-owner -f "$tmp"
    ;;
esac

# custom 포맷은 'PGDMP'로 시작한다. 빈 파일·에러 텍스트가 백업으로 남아 회전이 멀쩡한 것을 밀어내지 않게 한다.
[[ "$(head -c 5 "$tmp")" == "PGDMP" ]] || die "dump does not look like a pg_dump custom archive"
mv "$tmp" "$target"
log "wrote $target ($(du -h "$target" | cut -f1))"

# 회전: 최신 $keep개만 남긴다. 이 스크립트가 만든 이름 패턴만 건드린다.
mapfile -t old < <(find "$backup_dir" -maxdepth 1 -type f -name "${db}-*.dump" -printf '%f\n' | sort -r | tail -n +"$((keep + 1))")
for f in "${old[@]}"; do
  rm -f -- "${backup_dir:?}/$f"
  log "rotated out $f"
done
log "done (keeping $keep)"

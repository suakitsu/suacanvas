#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_PARENT="$(cd "$APP_DIR/.." && pwd)"
if [ "$(basename "$APP_PARENT")" = "releases" ]; then
  ROOT_DIR="$(cd "$APP_PARENT/.." && pwd)"
else
  ROOT_DIR="$APP_PARENT"
fi
ENV_FILE="${OPENIMAGE_ENV_FILE:-$ROOT_DIR/.env}"

cd "$APP_DIR"

if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "Docker Compose is not installed. Stop to avoid changing server packages automatically."
  exit 1
fi

if [ ! -f "$ENV_FILE" ] && [ -f "$APP_DIR/.env.example" ]; then
  cp "$APP_DIR/.env.example" "$ENV_FILE"
  echo "Created $ENV_FILE from .env.example. Change passwords and JWT_SECRET before public use."
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  ln -sfn "$ENV_FILE" "$APP_DIR/.env" 2>/dev/null || cp "$ENV_FILE" "$APP_DIR/.env"
fi

COMPOSE_PROJECT="${COMPOSE_PROJECT_NAME:-openimage_ai}"
BACKUP_ROOT="${OPENIMAGE_BACKUP_DIR:-$ROOT_DIR/backups}"
RETAIN="${OPENIMAGE_BACKUP_RETAIN:-7}"

compose() {
  if [ -f "$ENV_FILE" ]; then
    $COMPOSE --env-file "$ENV_FILE" --project-name "$COMPOSE_PROJECT" "$@"
  else
    $COMPOSE --project-name "$COMPOSE_PROJECT" "$@"
  fi
}

backup() {
  local stamp dir
  stamp="$(date +%Y%m%d-%H%M%S)"
  dir="$BACKUP_ROOT/$stamp"
  mkdir -p "$dir"
  cp "$ENV_FILE" "$dir/.env" 2>/dev/null || true
  compose ps > "$dir/compose-ps.txt" 2>/dev/null || true
  if compose exec -T postgres pg_dump -U "${POSTGRES_USER:-openimage}" -d "${POSTGRES_DB:-openimage}" > "$dir/postgres.sql" 2>/dev/null; then
    gzip "$dir/postgres.sql"
  else
    echo "Postgres dump skipped or failed; check database container before relying on this backup." > "$dir/postgres.warn"
  fi
  docker run --rm -v "${COMPOSE_PROJECT}_openimage_storage:/data" -v "$dir:/backup" alpine sh -c 'cd /data && tar -czf /backup/storage.tgz .' 2>/dev/null || true
  docker run --rm -v "${COMPOSE_PROJECT}_openimage_minio_data:/data" -v "$dir:/backup" alpine sh -c 'cd /data && tar -czf /backup/minio.tgz .' 2>/dev/null || true
  docker run --rm -v "${COMPOSE_PROJECT}_openimage_redis_data:/data" -v "$dir:/backup" alpine sh -c 'cd /data && tar -czf /backup/redis.tgz .' 2>/dev/null || true
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d | sort -r | tail -n +"$((RETAIN + 1))" | xargs -r rm -rf
  echo "Backup created: $dir"
}

deploy() {
  export DOCKER_BUILDKIT=1
  export COMPOSE_DOCKER_CLI_BUILD=1
  compose up -d --build --remove-orphans "$@"
  compose ps
}

rollback() {
  local target="${1:-}"
  if [ -z "$target" ] && [ -f "$ROOT_DIR/previous-release.txt" ]; then
    target="$(cat "$ROOT_DIR/previous-release.txt")"
  fi
  if [ -z "$target" ]; then
    echo "No rollback target found."
    exit 1
  fi
  if [ ! -d "$ROOT_DIR/$target" ] && [ ! -d "$target" ]; then
    echo "Rollback target not found: $target"
    exit 1
  fi
  cd "$ROOT_DIR"
  ln -sfn "$target" current
  cd current
  [ -f "$ENV_FILE" ] && { ln -sfn "$ENV_FILE" .env 2>/dev/null || cp "$ENV_FILE" .env; }
  export DOCKER_BUILDKIT=1
  export COMPOSE_DOCKER_CLI_BUILD=1
  compose up -d --build --remove-orphans
  compose ps
}

case "${1:-deploy}" in
  backup)
    backup
    ;;
  deploy)
    shift || true
    deploy "$@"
    ;;
  rollback)
    shift || true
    rollback "${1:-}"
    ;;
  *)
    deploy "$@"
    ;;
esac

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

if [ -n "${OPENIMAGE_ENV_FILE:-}" ]; then
  ENV_FILE="$OPENIMAGE_ENV_FILE"
elif [ -f "$ROOT_DIR/.env" ]; then
  ENV_FILE="$ROOT_DIR/.env"
else
  ENV_FILE="$APP_DIR/.env"
fi

cd "$APP_DIR"

if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "Docker Compose is not installed."
  exit 1
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  ln -sfn "$ENV_FILE" "$APP_DIR/.env" 2>/dev/null || cp "$ENV_FILE" "$APP_DIR/.env"
else
  echo "Environment file not found: $ENV_FILE"
  exit 1
fi

COMPOSE_PROJECT="${COMPOSE_PROJECT_NAME:-openimage_ai}"
BACKUP_ROOT="${OPENIMAGE_BACKUP_DIR:-$ROOT_DIR/backups}"
PREVIOUS_IMAGES_FILE="${OPENIMAGE_PREVIOUS_IMAGES_FILE:-$ROOT_DIR/previous-images.env}"

compose() {
  $COMPOSE --env-file "$ENV_FILE" --project-name "$COMPOSE_PROJECT" "$@"
}

require_var() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "Missing required env: $name"
    exit 1
  fi
}

compose_image_name() {
  echo "${COMPOSE_PROJECT}-$1:latest"
}

container_image_id() {
  docker inspect -f '{{.Image}}' "$1" 2>/dev/null || true
}

record_previous_images() {
  {
    echo "BACKEND_IMAGE_ID=$(container_image_id openimage_backend)"
    echo "USER_WEB_IMAGE_ID=$(container_image_id openimage_user_web)"
    echo "ADMIN_WEB_IMAGE_ID=$(container_image_id openimage_admin_web)"
    echo "RECORDED_AT=$(date -Iseconds)"
  } > "$PREVIOUS_IMAGES_FILE"
}

restore_previous_images() {
  [ -f "$PREVIOUS_IMAGES_FILE" ] || return 0
  # shellcheck disable=SC1090
  . "$PREVIOUS_IMAGES_FILE"
  [ -n "${BACKEND_IMAGE_ID:-}" ] && docker tag "$BACKEND_IMAGE_ID" "$(compose_image_name backend)" || true
  [ -n "${USER_WEB_IMAGE_ID:-}" ] && docker tag "$USER_WEB_IMAGE_ID" "$(compose_image_name user-web)" || true
  [ -n "${ADMIN_WEB_IMAGE_ID:-}" ] && docker tag "$ADMIN_WEB_IMAGE_ID" "$(compose_image_name admin-web)" || true
  compose up -d --no-build --no-deps backend user-web admin-web || true
}

backup_database() {
  local stamp dir
  stamp="$(date +%Y%m%d-%H%M%S)"
  dir="$BACKUP_ROOT/$stamp-release"
  mkdir -p "$dir"
  cp "$ENV_FILE" "$dir/.env" 2>/dev/null || true
  readlink -f "$APP_DIR" > "$dir/release.txt" 2>/dev/null || true
  compose ps > "$dir/compose-ps.txt" 2>/dev/null || true
  compose exec -T postgres pg_dump -U "${POSTGRES_USER:-openimage}" -d "${POSTGRES_DB:-openimage}" > "$dir/postgres.sql"
  gzip "$dir/postgres.sql"
  echo "Backup created: $dir"
}

registry_login() {
  if [ -n "${OPENIMAGE_REGISTRY_USERNAME:-}" ] && [ -n "${OPENIMAGE_REGISTRY_PASSWORD:-}" ]; then
    printf '%s' "$OPENIMAGE_REGISTRY_PASSWORD" | docker login "${OPENIMAGE_REGISTRY_HOST:-ghcr.io}" -u "$OPENIMAGE_REGISTRY_USERNAME" --password-stdin >/dev/null
  fi
}

pull_and_tag() {
  require_var OPENIMAGE_BACKEND_IMAGE
  require_var OPENIMAGE_USER_WEB_IMAGE
  require_var OPENIMAGE_ADMIN_WEB_IMAGE
  pull_image "$OPENIMAGE_BACKEND_IMAGE"
  pull_image "$OPENIMAGE_USER_WEB_IMAGE"
  pull_image "$OPENIMAGE_ADMIN_WEB_IMAGE"
  docker tag "$OPENIMAGE_BACKEND_IMAGE" "$(compose_image_name backend)"
  docker tag "$OPENIMAGE_USER_WEB_IMAGE" "$(compose_image_name user-web)"
  docker tag "$OPENIMAGE_ADMIN_WEB_IMAGE" "$(compose_image_name admin-web)"
}

pull_image() {
  local image="$1"
  local attempts="${OPENIMAGE_PULL_ATTEMPTS:-2}"
  local timeout_seconds="${OPENIMAGE_PULL_TIMEOUT_SECONDS:-600}"
  local attempt
  for attempt in $(seq 1 "$attempts"); do
    echo "Pulling $image (attempt $attempt/$attempts, timeout ${timeout_seconds}s)"
    if timeout "$timeout_seconds" docker pull "$image"; then
      return 0
    fi
    echo "Pull failed or timed out: $image"
    sleep 5
  done
  return 1
}

wait_backend() {
  local base_url
  base_url="$(backend_smoke_url)"
  for _ in $(seq 1 40); do
    if curl -fsS "$base_url/api/public/ping" >/dev/null; then
      echo "Backend smoke OK: $base_url"
      return 0
    fi
    sleep 3
  done
  echo "Backend smoke failed: $base_url"
  return 1
}

backend_smoke_url() {
  if [ -n "${OPENIMAGE_BACKEND_SMOKE_URL:-}" ]; then
    printf '%s\n' "${OPENIMAGE_BACKEND_SMOKE_URL%/}"
    return 0
  fi
  local binding host port
  binding="${BACKEND_PORT:-18080}"
  if [[ "$binding" == http://* || "$binding" == https://* ]]; then
    printf '%s\n' "${binding%/}"
    return 0
  fi
  if [[ "$binding" == *:* ]]; then
    host="${binding%:*}"
    port="${binding##*:}"
    if [ -z "$host" ] || [ "$host" = "0.0.0.0" ] || [ "$host" = "::" ]; then
      host="127.0.0.1"
    fi
  else
    host="127.0.0.1"
    port="$binding"
  fi
  printf 'http://%s:%s\n' "$host" "$port"
}

rollback_on_failure() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ "${OPENIMAGE_ROLLBACK_ON_FAILURE:-1}" = "1" ]; then
    echo "Deploy failed, restoring previous service images..."
    restore_previous_images
  fi
  exit "$rc"
}
trap rollback_on_failure EXIT

if [ "${1:-deploy}" = "rollback" ]; then
  restore_previous_images
  exit 0
fi

registry_login
record_previous_images

if [ "${OPENIMAGE_DEPLOY_BACKUP:-0}" = "1" ]; then
  backup_database
fi

pull_and_tag
compose up -d --no-build --no-deps backend user-web admin-web
compose ps
wait_backend

if [ -n "${OPENIMAGE_PUBLIC_SMOKE_URL:-}" ]; then
  "$SCRIPT_DIR/smoke-test.sh" "$OPENIMAGE_PUBLIC_SMOKE_URL"
fi

trap - EXIT
echo "Release deploy completed."

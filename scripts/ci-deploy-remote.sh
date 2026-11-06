#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:-staging}"
if [ "$TARGET" != "staging" ] && [ "$TARGET" != "production" ]; then
  echo "Usage: $0 staging|production"
  exit 1
fi

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_KEY:?DEPLOY_KEY is required}"
: "${DEPLOY_DIR:?DEPLOY_DIR is required}"
: "${IMAGE_NAMESPACE:?IMAGE_NAMESPACE is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

BACKEND_IMAGE="${IMAGE_NAMESPACE}/backend:${IMAGE_TAG}"
USER_WEB_IMAGE="${IMAGE_NAMESPACE}/user-web:${IMAGE_TAG}"
ADMIN_WEB_IMAGE="${IMAGE_NAMESPACE}/admin-web:${IMAGE_TAG}"
RELEASE_ID="ci-${IMAGE_TAG:0:12}"
ARCHIVE="/tmp/openimage-${RELEASE_ID}.tgz"
REMOTE_ARCHIVE="/tmp/openimage-${RELEASE_ID}.tgz"
SSH_KEY_FILE="/tmp/openimage-deploy-key"

if [ "$TARGET" = "production" ]; then
  DEPLOY_BACKUP=1
  PUBLIC_SMOKE_URL="${DEPLOY_PUBLIC_SMOKE_URL:-https://suakitsu.com}"
else
  DEPLOY_BACKUP=0
  PUBLIC_SMOKE_URL="${DEPLOY_PUBLIC_SMOKE_URL:-http://${DEPLOY_HOST}:18080}"
fi

cleanup() {
  rm -f "$ARCHIVE" "$SSH_KEY_FILE"
}
trap cleanup EXIT

printf '%s\n' "$DEPLOY_KEY" > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"

# Package committed files only; local .env, uploads and signing keys stay private.
git archive --format=tar.gz --output="$ARCHIVE" HEAD

scp -i "$SSH_KEY_FILE" -o StrictHostKeyChecking=no "$ARCHIVE" "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_ARCHIVE}"

ssh -i "$SSH_KEY_FILE" -o StrictHostKeyChecking=no "${DEPLOY_USER}@${DEPLOY_HOST}" "mkdir -p '${DEPLOY_DIR}/releases/${RELEASE_ID}' && tar -xzf '${REMOTE_ARCHIVE}' -C '${DEPLOY_DIR}/releases/${RELEASE_ID}'"

ssh -i "$SSH_KEY_FILE" -o StrictHostKeyChecking=no "${DEPLOY_USER}@${DEPLOY_HOST}" "cat > /tmp/openimage-release-env-${RELEASE_ID}" <<EOF_ENV
OPENIMAGE_BACKEND_IMAGE=${BACKEND_IMAGE}
OPENIMAGE_USER_WEB_IMAGE=${USER_WEB_IMAGE}
OPENIMAGE_ADMIN_WEB_IMAGE=${ADMIN_WEB_IMAGE}
OPENIMAGE_DEPLOY_BACKUP=${DEPLOY_BACKUP}
OPENIMAGE_PUBLIC_SMOKE_URL=${PUBLIC_SMOKE_URL}
OPENIMAGE_REGISTRY_HOST=ghcr.io
OPENIMAGE_REGISTRY_USERNAME=${GHCR_USERNAME:-}
OPENIMAGE_REGISTRY_PASSWORD=${GHCR_TOKEN:-}
OPENIMAGE_ROLLBACK_ON_FAILURE=1
EOF_ENV

ssh -i "$SSH_KEY_FILE" -o StrictHostKeyChecking=no "${DEPLOY_USER}@${DEPLOY_HOST}" "
  set -euo pipefail
  release='${DEPLOY_DIR}/releases/${RELEASE_ID}'
  cd '${DEPLOY_DIR}'
  if [ -f current/.env ]; then
    cp current/.env \"\$release/.env\"
  elif [ -f .env ]; then
    cp .env \"\$release/.env\"
  else
    cp \"\$release/.env.example\" \"\$release/.env\"
  fi
  if [ -L current ]; then
    readlink current > previous-release.txt || true
  fi
  ln -sfn \"\$release\" current
  chmod +x current/scripts/*.sh
  set -a
  . /tmp/openimage-release-env-${RELEASE_ID}
  set +a
  cd current
  ./scripts/release-deploy.sh
  rm -f /tmp/openimage-release-env-${RELEASE_ID} '${REMOTE_ARCHIVE}'
"

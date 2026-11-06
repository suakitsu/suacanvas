#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
fetch_pinned() {
  local name="$1" repository="$2" commit="$3"
  local dest="$APP_DIR/third_party/canvas-lab/$name"
  mkdir -p "$(dirname "$dest")"
  if [ ! -d "$dest/.git" ]; then
    git clone --filter=blob:none --no-checkout "$repository" "$dest"
  elif [ -n "$(git -C "$dest" status --porcelain)" ]; then
    echo "Refusing to overwrite modified upstream source: $name" >&2
    return 1
  fi
  git -C "$dest" fetch --depth 1 origin "$commit"
  git -C "$dest" checkout --detach "$commit"
  test "$(git -C "$dest" rev-parse HEAD)" = "$commit"
  test -f "$dest/LICENSE"
  printf '%s pinned at %s\n' "$name" "$commit"
}

fetch_pinned infinite-canvas https://github.com/basketikun/infinite-canvas.git 705fdd9b2f7bfe6fd58156fd5c1cdb1ad251c767
fetch_pinned nova-image-studio https://github.com/tianjiangqiji/nova-image-studio.git 7768f3f8d7f47e04c6d18572837a086c7a533161

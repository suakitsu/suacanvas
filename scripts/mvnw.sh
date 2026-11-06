#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CURRENT_DIR="$(pwd)"
WORK_DIR="/workspace/openImage"

case "$CURRENT_DIR" in
  "$REPO_ROOT"/*)
    if [ -f "$CURRENT_DIR/pom.xml" ]; then
      rel="${CURRENT_DIR#$REPO_ROOT/}"
      WORK_DIR="/workspace/$rel"
    fi
    ;;
  "$REPO_ROOT")
    if [ -f "$CURRENT_DIR/pom.xml" ]; then
      WORK_DIR="/workspace"
    fi
    ;;
esac

mkdir -p "$HOME/.m2"
docker run --rm \
  -v "$REPO_ROOT:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -w "$WORK_DIR" \
  maven:3.9-eclipse-temurin-17 \
  mvn "$@"

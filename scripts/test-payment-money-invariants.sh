#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

(
  cd "$ROOT_DIR/backend"
  ./mvnw test
)

(
  cd "$ROOT_DIR/frontend"
  NODE_OPTIONS=--no-experimental-webstorage npm test
  npm run build
  npm run lint
)

"$ROOT_DIR/scripts/test-payment-concurrency.sh"
"$ROOT_DIR/scripts/test-payment-full-stack.sh"

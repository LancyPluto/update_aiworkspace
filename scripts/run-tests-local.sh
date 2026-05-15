#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DIR="$ROOT_DIR/agent-service"
BACKEND_DIR="$ROOT_DIR/backend"

BREW_PREFIX="${HOMEBREW_PREFIX:-$(brew --prefix)}"
PYTHON_BIN="${PYTHON_BIN:-$BREW_PREFIX/opt/python@3.11/bin/python3.11}"
JAVA_HOME_DEFAULT="$BREW_PREFIX/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
MAVEN_BIN_DIR="$BREW_PREFIX/opt/maven/bin"

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="$MAVEN_BIN_DIR:$JAVA_HOME/bin:$BREW_PREFIX/opt/python@3.11/bin:$PATH"

if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Python 3.11 is missing. Run scripts/setup-test-env.sh first."
  exit 1
fi

if [[ ! -x "$AGENT_DIR/.venv/bin/pytest" ]]; then
  echo "agent-service virtualenv is missing. Run scripts/setup-test-env.sh first."
  exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is missing from PATH. Run scripts/setup-test-env.sh first."
  exit 1
fi

echo "[1/2] Running agent-service tests"
"$AGENT_DIR/.venv/bin/pytest" -q "$AGENT_DIR/tests"

echo "[2/2] Running backend tests"
(
  cd "$BACKEND_DIR"
  mvn test
)

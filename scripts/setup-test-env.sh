#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DIR="$ROOT_DIR/agent-service"

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required. Install it first: https://brew.sh/"
  exit 1
fi

echo "[1/4] Installing Python 3.11, Java 17, and Maven"
HOMEBREW_NO_AUTO_UPDATE=1 brew install python@3.11 openjdk@17 maven

BREW_PREFIX="$(brew --prefix)"
PYTHON_BIN="$(brew --prefix python@3.11)/bin/python3.11"
MAVEN_BIN_DIR="$(brew --prefix maven)/bin"
JAVA_HOME_DIR="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"

if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "python3.11 was not found after install."
  exit 1
fi

if [[ ! -d "$JAVA_HOME_DIR" ]]; then
  echo "JAVA_HOME for openjdk@17 was not found."
  exit 1
fi

echo "[2/4] Creating agent-service virtualenv"
"$PYTHON_BIN" -m venv "$AGENT_DIR/.venv"

echo "[3/4] Installing agent-service Python dependencies"
"$AGENT_DIR/.venv/bin/python" -m pip install --upgrade pip setuptools wheel
"$AGENT_DIR/.venv/bin/python" -m pip install -r "$AGENT_DIR/requirements.txt"

cat <<EOF
[4/4] Environment ready

Use these commands in a new shell:

  export PATH="$MAVEN_BIN_DIR:\$PATH"
  export JAVA_HOME="$JAVA_HOME_DIR"
  export PATH="\$JAVA_HOME/bin:$BREW_PREFIX/opt/python@3.11/bin:\$PATH"

Agent service tests:
  "$AGENT_DIR/.venv/bin/pytest" -q "$AGENT_DIR/tests"

Backend tests:
  cd "$ROOT_DIR/backend" && mvn test
EOF

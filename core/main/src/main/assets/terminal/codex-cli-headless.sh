#!/usr/bin/env bash
set -e

source "$LOCAL/bin/utils" >/dev/null 2>&1 || true

# Support both agent-specific and generic IDE bridge env vars
IDE_PORT="${CODEX_IDE_SERVER_PORT:-${IDE_SERVER_PORT:-}}"
IDE_TOKEN="${CODEX_IDE_AUTH_TOKEN:-${IDE_AUTH_TOKEN:-}}"
IDE_WS="${CODEX_IDE_WORKSPACE_PATH:-${IDE_WORKSPACE_PATH:-}}"

if [ -n "$IDE_TOKEN" ]; then
  export IDE_AUTH_TOKEN="$IDE_TOKEN"
fi

workspace_dir="${IDE_WS%%:*}"
target_dir="${WKDIR:-${workspace_dir:-$HOME}}"
cd "$target_dir" 2>/dev/null || cd "$workspace_dir" 2>/dev/null || cd "$HOME"
export WKDIR="$(pwd)"

export NO_UPDATE_NOTIFIER=1
export UV_THREADPOOL_SIZE=1
export PATH="/usr/local/bin:/usr/bin:$HOME/.local/bin:$LOCAL/bin:$PATH"
export EDITOR=vim
export VISUAL=vim
export CODEX_QUIET_MODE=1

log() { printf '%s\n' "$*" >&2; }
info() { log "[INFO] $*"; }
warn() { log "[WARN] $*"; }
error() { log "[ERROR] $*"; }

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  log "Node.js/npm is required. Installing Node.js LTS..."
  install_nodejs >/dev/null 2>&1 || true
fi

if ! command -v codex >/dev/null 2>&1 && [ ! -x "$LOCAL/bin/codex" ]; then
  log "Installing Codex CLI..."
  npm install -g --prefix /usr @openai/codex >/dev/null 2>&1 || \
  npm install -g --prefix "$LOCAL" @openai/codex >/dev/null 2>&1 || {
    log "Codex CLI installation failed"
  }
fi

# Configure the Xed Editor IDE bridge as an MCP server
configure_xed_mcp codex "$IDE_PORT" "$IDE_TOKEN" >/dev/null 2>&1 || true

CODEX_BIN="$(command -v codex 2>/dev/null || true)"
if [ -z "$CODEX_BIN" ] && [ -x "$LOCAL/bin/codex" ]; then
  CODEX_BIN="$LOCAL/bin/codex"
fi
if [ -z "$CODEX_BIN" ]; then
  CODEX_BIN="codex"
fi

exec "$CODEX_BIN" "$@"

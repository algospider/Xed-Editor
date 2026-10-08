#!/usr/bin/env bash
set -e

source "$LOCAL/bin/utils" >/dev/null 2>&1 || true

# Support both generic and Gemini-specific IDE bridge env vars
IDE_PORT="${IDE_SERVER_PORT:-${GEMINI_CLI_IDE_SERVER_PORT:-}}"
IDE_TOKEN="${IDE_AUTH_TOKEN:-${GEMINI_CLI_IDE_AUTH_TOKEN:-}}"
IDE_WS="${IDE_WORKSPACE_PATH:-${GEMINI_CLI_IDE_WORKSPACE_PATH:-}}"

workspace_dir="${IDE_WS%%:*}"
target_dir="${WKDIR:-${workspace_dir:-$HOME}}"
cd "$target_dir" 2>/dev/null || cd "$workspace_dir" 2>/dev/null || cd "$HOME"
export WKDIR="$(pwd)"

export NO_UPDATE_NOTIFIER=1
export UV_THREADPOOL_SIZE=1
export PATH="/usr/local/bin:/usr/bin:$HOME/.local/bin:$LOCAL/bin:$PATH"
export EDITOR=vim
export VISUAL=vim

log() { printf '%s\n' "$*" >&2; }
info() { log "[INFO] $*"; }
warn() { log "[WARN] $*"; }
error() { log "[ERROR] $*"; }

# Configure the Xed Editor IDE bridge as an MCP server
configure_xed_mcp opencode "$IDE_PORT" "$IDE_TOKEN" >/dev/null 2>&1 || true

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  log "Node.js/npm is required. Installing Node.js LTS..."
  install_nodejs >/dev/null 2>&1 || true
fi

if ! command -v opencode >/dev/null 2>&1 && [ ! -x "$LOCAL/bin/opencode" ]; then
  log "Installing OpenCode CLI..."
  npm install -g --prefix /usr --allow-scripts=opencode-ai opencode-ai@latest >/dev/null 2>&1 || \
  npm install -g --prefix "$LOCAL" --allow-scripts=opencode-ai opencode-ai@latest >/dev/null 2>&1 || {
    log "OpenCode CLI installation failed"
  }
  if ! command -v opencode >/dev/null 2>&1 && [ ! -x "$LOCAL/bin/opencode" ]; then
    for postinstall in /usr/lib/node_modules/opencode-ai/postinstall.mjs "$LOCAL/lib/node_modules/opencode-ai/postinstall.mjs"; do
      if [ -f "$postinstall" ]; then
        node "$postinstall" >/dev/null 2>&1 || true
      fi
    done
  fi
fi

OPENCODE_BIN="$(command -v opencode 2>/dev/null || true)"
if [ -z "$OPENCODE_BIN" ] && [ -x "$LOCAL/bin/opencode" ]; then
  OPENCODE_BIN="$LOCAL/bin/opencode"
fi
if [ -z "$OPENCODE_BIN" ]; then
  OPENCODE_BIN="opencode"
fi

exec "$OPENCODE_BIN" "$@"

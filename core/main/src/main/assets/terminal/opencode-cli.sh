#!/usr/bin/env bash
set -e

source "$LOCAL/bin/utils"

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

info "Starting OpenCode CLI..."
info "Workspace: $WKDIR"

# Configure the Xed Editor IDE bridge as an MCP server
configure_xed_mcp opencode "$IDE_PORT" "$IDE_TOKEN"

ensure_node() {
  if ! command_exists node || ! command_exists npm; then
    warn "Node.js/npm is required. Installing..."
    install_nodejs
  fi
}

ensure_opencode() {
  if ! command_exists opencode && [ ! -x "$LOCAL/bin/opencode" ]; then
    info "Installing OpenCode CLI..."
    npm install -g --prefix /usr --allow-scripts=opencode-ai opencode-ai@latest 2>&1 || \
    npm install -g --prefix "$LOCAL" --allow-scripts=opencode-ai opencode-ai@latest 2>&1 || {
      warn "OpenCode CLI installation failed"
      return 1
    }
    if ! command_exists opencode && [ ! -x "$LOCAL/bin/opencode" ]; then
      for postinstall in /usr/lib/node_modules/opencode-ai/postinstall.mjs "$LOCAL/lib/node_modules/opencode-ai/postinstall.mjs"; do
        if [ -f "$postinstall" ]; then
          node "$postinstall" 2>&1 || true
        fi
      done
    fi
    info "OpenCode CLI installed successfully."
  fi
}

ensure_node
ensure_opencode

OPENCODE_BIN="$(command -v opencode 2>/dev/null || true)"
if [ -z "$OPENCODE_BIN" ] && [ -x "$LOCAL/bin/opencode" ]; then
  OPENCODE_BIN="$LOCAL/bin/opencode"
fi
if [ -z "$OPENCODE_BIN" ]; then
  OPENCODE_BIN="opencode"
fi

exec "$OPENCODE_BIN" "$@"

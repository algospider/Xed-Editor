#!/usr/bin/env bash
set -e

source "$LOCAL/bin/utils"

IDE_PORT="${CLAUDE_IDE_SERVER_PORT:-${CLAUDE_CODE_IDE_SERVER_PORT:-${IDE_SERVER_PORT:-}}}"
IDE_TOKEN="${CLAUDE_IDE_AUTH_TOKEN:-${CLAUDE_CODE_IDE_AUTH_TOKEN:-${IDE_AUTH_TOKEN:-}}}"
IDE_WS="${CLAUDE_IDE_WORKSPACE_PATH:-${CLAUDE_CODE_IDE_WORKSPACE_PATH:-${IDE_WORKSPACE_PATH:-}}}"

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

info "Starting Claude Code..."
info "Workspace: $WKDIR"

ensure_node

ensure_claude() {
  if ! command_exists claude && [ ! -x "$LOCAL/bin/claude" ]; then
    info "Installing Claude Code..."
    npm install -g --prefix /usr --allow-scripts=@anthropic-ai/claude-code @anthropic-ai/claude-code 2>&1 || \
    npm install -g --prefix /usr @anthropic-ai/claude-code 2>&1 || {
      warn "Claude Code installation failed"
      return 1
    }
    if ! command_exists claude; then
      for postinstall in /usr/lib/node_modules/@anthropic-ai/claude-code/install.cjs "$LOCAL/lib/node_modules/@anthropic-ai/claude-code/install.cjs"; do
        if [ -f "$postinstall" ]; then
          node "$postinstall" 2>&1 || true
        fi
      done
    fi
    info "Claude Code installed successfully."
  fi
}

ensure_claude

configure_xed_mcp claude "$IDE_PORT" "$IDE_TOKEN"

CLAUDE_BIN="$(command -v claude 2>/dev/null || true)"
if [ -z "$CLAUDE_BIN" ] && [ -x "$LOCAL/bin/claude" ]; then
  CLAUDE_BIN="$LOCAL/bin/claude"
fi
if [ -z "$CLAUDE_BIN" ]; then
  CLAUDE_BIN="claude"
fi

info "Starting Claude Code in $(pwd)"
exec "$CLAUDE_BIN" "$@"

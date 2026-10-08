# shellcheck disable=SC2034
force_color_prompt=yes
shopt -s checkwinsize

export PROOT_NO_SECCOMP=1
export PROOT_ASSUME_NEW_SECCOMP=1
export PROOT_F2FS_WORKAROUND=1
export PROOT_L2S_DIR="${LOCAL:-/data/user/0/com.rk.xededitor.debug}/sandbox/.l2s"
mkdir -p "$PROOT_L2S_DIR" 2>/dev/null || true
export UV_THREADPOOL_SIZE=1

# --- PATH: guest-first, Debian-standard order -------------------------------
# /usr/local/bin precedes /usr/bin so the dpkg/apt fixup wrappers installed
# below take effect everywhere (including sudo's secure_path). Host Android
# directories (/system, /vendor, ...) are dropped entirely: executing host
# binaries inside proot fails with ENOSYS/EPERM on modern kernels
# (Samsung One UI / Android 15+, kernel 6.6+).
export SHELL="bash"
_LOCAL_BIN="${LOCAL:-/data/user/0/com.rk.xededitor.debug}/bin"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:$_LOCAL_BIN"
unset _LOCAL_BIN
export PS1="\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\] \\$ "

source "$LOCAL/bin/utils"

# --- proot link2symlink fixups ----------------------------------------------
# Android blocks link(2); proot's --link2symlink fakes hardlinks using
# ".l2s.*" files. Upstream proot has known bugs here (termux/proot #252 and
# #393; fixes in PRs #394/#395 not yet merged): a dangling ".l2s.*"
# intermediate makes the next link() fail with EPERM, so dpkg dies with
#   dpkg: error: error creating new backup file '/var/lib/dpkg/status-old':
#   Permission denied            -> apt exits with code 100 / 2
# Scrubbing broken chains before dpkg/apt runs restores a healthy database.
l2s_fixup() {
    local db=/var/lib/dpkg f b
    [ -d "$db" ] || return 0
    # 1. dangling intermediates (.l2s.XNNNN -> missing payload)
    for f in "$db"/.l2s.*; do
        [ -L "$f" ] && [ ! -e "$f" ] && rm -f "$f" 2>/dev/null
    done
    # 2. backup symlinks left dangling (status-old, diversions-old, *-new)
    for f in "$db"/*-old "$db"/*-new; do
        [ -L "$f" ] && [ ! -e "$f" ] && rm -f "$f" 2>/dev/null
    done
    # 3. orphaned payloads whose intermediate is gone
    for f in "$db"/.l2s.*.*; do
        [ -f "$f" ] || continue
        b=${f##*/}
        [ -e "$db/${b%.*}" ] || rm -f "$f" 2>/dev/null
    done
    return 0
}

# Install dpkg/apt/apt-get wrappers that run the cleanup above before the
# real tool. Written on every shell start so they always match this build.
# Locations: /usr/local/bin (guest PATH + sudo secure_path) and $LOCAL/bin
# (host-PATH contexts such as ubuntuProcess / agent sessions).
install_fixup_wrappers() {
    local d b tmp="/tmp/.xed-fixup.$$"
    cat > "$tmp" <<'WRAP'
#!/bin/sh
# Xed fixup wrapper for proot --link2symlink bugs (termux/proot #252/#393):
# dangling .l2s.* chains in /var/lib/dpkg break dpkg's atomic backup with
# "Permission denied" (apt error 100/2). Scrub them, then run the real tool.
db=/var/lib/dpkg
if [ -d "$db" ]; then
    for f in "$db"/.l2s.*; do
        [ -L "$f" ] && [ ! -e "$f" ] && rm -f "$f" 2>/dev/null
    done
    for f in "$db"/*-old "$db"/*-new; do
        [ -L "$f" ] && [ ! -e "$f" ] && rm -f "$f" 2>/dev/null
    done
    for f in "$db"/.l2s.*.*; do
        [ -f "$f" ] || continue
        b=${f##*/}
        [ -e "$db/${b%.*}" ] || rm -f "$f" 2>/dev/null
    done
fi
exec "/usr/bin/${0##*/}" "$@"
WRAP
    for d in /usr/local/bin /usr/local/sbin; do
        [ -n "$d" ] || continue
        [ -d "$d" ] || mkdir -p "$d" 2>/dev/null || continue
        for b in dpkg apt apt-get; do
            cp "$tmp" "$d/$b" 2>/dev/null && chmod 755 "$d/$b" 2>/dev/null
        done
    done
    rm -f "$tmp"
    return 0
}
install_fixup_wrappers

runpkg() {
    sh -c "$*"
}

# Set timezone
CONTAINER_TIMEZONE="UTC"  # or any timezone like "Asia/Kolkata"

# Symlink /etc/localtime to the desired timezone
ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime

# Write the timezone string to /etc/timezone
echo "$CONTAINER_TIMEZONE" > /etc/timezone

# Reconfigure tzdata once per rootfs — perl under proot is slow, no need to
# pay that cost on every shell start.
if [ ! -f /.cache/.tz_configured ]; then
    mkdir -p /.cache 2>/dev/null || true
    runpkg "env DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive tzdata" >/dev/null 2>&1 || true
    touch /.cache/.tz_configured 2>/dev/null || true
fi


if [[ -f ~/.bashrc ]]; then
    # shellcheck disable=SC1090
    source ~/.bashrc
fi


ensure_packages_once() {
    local marker_file="/.cache/.packages_ensured"
    local PACKAGES=("command-not-found" "sudo" "xkb-data")
    local log=/var/log/xed-setup.log
    local attempt rc pkg

    # Exit early if already done
    [[ -f "$marker_file" ]] && return 0

    mkdir -p /.cache /var/log /var/lock 2>/dev/null || true

    # Cross-session lock: app launch can start several proot sessions at once
    # (terminal + agent sheets). Two dpkg/apt processes interleaving on the
    # same database corrupt proot's link2symlink state (link() then fails
    # with ENOSYS/EPERM). Only one session may run setup at a time.
    local lock=/var/lock/xed-setup waited=0
    while ! mkdir "$lock" 2>/dev/null; do
        [[ -f "$marker_file" ]] && return 0
        waited=$((waited + 2))
        if [ "$waited" -ge 300 ]; then
            # Lock holder died mid-setup — reclaim and keep waiting our turn
            rmdir "$lock" 2>/dev/null || waited=0
            continue
        fi
        sleep 2
    done
    # Double-check the marker once we own the lock (another session may have
    # finished setup while we were waiting).
    if [[ -f "$marker_file" ]]; then
        rmdir "$lock" 2>/dev/null || true
        return 0
    fi

    echo 'APT::Install-Recommends "false";' > /etc/apt/apt.conf.d/99norecommends
    echo 'APT::Install-Suggests "false";' >> /etc/apt/apt.conf.d/99norecommends
    # proot fakeroot can't setresuid to _apt — run apt methods as root
    echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/99proot-sandbox 2>/dev/null || true

    l2s_fixup

    # A previous session may have died mid-install ("dpkg was interrupted")
    runpkg "dpkg --configure -a" >>"$log" 2>&1 || true

    # Check for missing packages
    local MISSING=()
    for pkg in "${PACKAGES[@]}"; do
        if ! dpkg -s "$pkg" >/dev/null 2>&1; then
            MISSING+=("$pkg")
        fi
    done

    # If nothing missing, just mark as done
    if [ ${#MISSING[@]} -eq 0 ]; then
        touch "$marker_file"
        rmdir "$lock" 2>/dev/null || true
        return 0
    fi

    info "Installing missing packages: ${MISSING[*]}"

    for attempt in 1 2 3; do
        {
            echo "=== setup attempt $attempt ($(date -u '+%F %T') UTC): ${MISSING[*]} ==="
            runpkg "apt -o Acquire::Retries=5 update" &&
                runpkg "DEBIAN_FRONTEND=noninteractive apt -o Acquire::Retries=5 -o Dpkg::Options::=--force-confold install -y ${MISSING[*]}"
        } 2>&1 | tee -a "$log"
        rc=${PIPESTATUS[0]}
        [ "$rc" -eq 0 ] && break
        warn "Package install attempt $attempt failed (rc=$rc), retrying…"
        l2s_fixup
        runpkg "dpkg --configure -a" >>"$log" 2>&1 || true
        sleep 2
    done

    if [ "$rc" -eq 0 ]; then
        touch "$marker_file"
        runpkg "update-command-not-found" >/dev/null 2>&1 || true
        rmdir "$lock" 2>/dev/null || true
        info "Setup complete."
        return 0
    fi

    error "Failed to install packages (see $log). Reopen the terminal to retry."
    rmdir "$lock" 2>/dev/null || true
    return 1
}


ensure_packages_once
unset -f ensure_packages_once

if [ -x /usr/lib/command-not-found -o -x /usr/share/command-not-found/command-not-found ]; then
	function command_not_found_handle {
	        # check because c-n-f could've been removed in the meantime
                if [ -x /usr/lib/command-not-found ]; then
		   /usr/lib/command-not-found -- "$1"
                   return $?
                elif [ -x /usr/share/command-not-found/command-not-found ]; then
		   /usr/share/command-not-found/command-not-found -- "$1"
                   return $?
		else
		   printf "%s: command not found\n" "$1" >&2
		   return 127
		fi
	}
fi


alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
alias pkg='apt'

if [[ -f /initrc ]]; then
    # shellcheck disable=SC1090
    source /initrc
fi

# 'groups' shim — host /system/bin/groups fails under proot on some kernels
groups() {
    /usr/bin/groups "$@" 2>/dev/null || /bin/groups "$@" 2>/dev/null || id -Gn 2>/dev/null || true
}

# shellcheck disable=SC2164
# Land in a valid guest dir silently — host absolute paths can fail under
# proot on some kernels, so fall back through guest homes.
cd "$WKDIR" 2>/dev/null || cd /home 2>/dev/null || cd /root 2>/dev/null || cd / 2>/dev/null || true

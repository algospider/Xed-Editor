#!/bin/sh
# Botix Terminal Setup - Modern Android Compatible
# Runs once on first terminal launch to extract and configure Ubuntu container

set -e

export PROOT_NO_SECCOMP=1
export PROOT_ASSUME_NEW_SECCOMP=1
export PROOT_F2FS_WORKAROUND=1
export PROOT_L2S_DIR="$LOCAL/sandbox/.l2s"
mkdir -p "$PROOT_L2S_DIR" 2>/dev/null || true

source "$LOCAL/bin/utils"

# --- one setup at a time ---------------------------------------------------
# A single app launch can start several sessions (intent + SessionService);
# without this, two setups download to the same .part file and corrupt each
# other's tarball. Later sessions wait until the first finishes, then go
# straight to the shell.
if [ -f "$LOCAL/.terminal_setup_ok_DO_NOT_REMOVE" ]; then
    if [ $# -gt 0 ]; then sh "$@"; else clear; sh "$LOCAL/bin/sandbox"; fi
    exit $?
fi

SETUP_LOCK="$LOCAL/.setup_lock"
waited=0
while ! mkdir "$SETUP_LOCK" 2>/dev/null; do
    if [ -f "$LOCAL/.terminal_setup_ok_DO_NOT_REMOVE" ]; then
        # first session finished setup — just launch
        if [ $# -gt 0 ]; then sh "$@"; else clear; sh "$LOCAL/bin/sandbox"; fi
        exit $?
    fi
    waited=$((waited + 2))
    if [ "$waited" -ge 600 ]; then
        # holder died mid-setup — reclaim
        rmdir "$SETUP_LOCK" 2>/dev/null || waited=0
        continue
    fi
    sleep 2
done
# We own the lock; release it on any exit (success or failure).
trap 'rmdir "$SETUP_LOCK" 2>/dev/null' EXIT

info "Extracting the Ubuntu container…"

# --- Rootfs download (BEFORE extraction), with LIVE progress in terminal ---
# Full Ubuntu base is ~30MB. Realtime curl progress bar shows here so a slow
# network doesn't look stuck. Re-downloads if missing / too small / bad magic.
ROOTFS_MIN_BYTES=26214400
NEED_DL=0
if [ ! -f "$TMP_DIR/sandbox.tar.gz" ]; then
    NEED_DL=1
else
    SZ=$(stat -c %s "$TMP_DIR/sandbox.tar.gz" 2>/dev/null || echo 0)
    if [ "${SZ:-0}" -lt "$ROOTFS_MIN_BYTES" ]; then
        NEED_DL=1
    elif ! head -c 2 "$TMP_DIR/sandbox.tar.gz" 2>/dev/null | od -A n -t x1 2>/dev/null | grep -q "1f *8b"; then
        NEED_DL=1
    fi
fi
if [ "$NEED_DL" = 1 ]; then
    if [ -z "${ROOTFS_URL:-}" ]; then
        error "Rootfs URL unknown and archive missing — reopen terminal to retry."
        exit 1
    fi
    if ! command -v curl >/dev/null 2>&1; then
        error "curl not found and rootfs missing — cannot download."
        exit 1
    fi
    rm -f "$TMP_DIR/sandbox.tar.gz" "$TMP_DIR/sandbox.tar.gz.part"
    info "Downloading Ubuntu base (~30MB, one-time)…"
    echo "URL: $ROOTFS_URL"
    if ! curl -fSL --retry 2 -# -o "$TMP_DIR/sandbox.tar.gz.part" "$ROOTFS_URL"; then
        error "Download failed — check internet and reopen terminal to retry."
        rm -f "$TMP_DIR/sandbox.tar.gz.part"
        exit 1
    fi
    mv "$TMP_DIR/sandbox.tar.gz.part" "$TMP_DIR/sandbox.tar.gz"
    SZ=$(stat -c %s "$TMP_DIR/sandbox.tar.gz" 2>/dev/null || echo 0)
    info "Downloaded $(expr "${SZ:-0}" / 1048576) MB."
fi

# Build proot ARGS
ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
  /linkerconfig/ld.config.txt \
  /linkerconfig/com.android.art/ld.config.txt \
  /plat_property_contexts /property_contexts; do

  if [ -e "$system_mnt" ]; then
    system_mnt=$(realpath "$system_mnt")
    ARGS="$ARGS -b ${system_mnt}"
  fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PRIVATE_DIR"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PRIVATE_DIR"

ARGS="$ARGS -b /sys"

ARGS="$ARGS -r /"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

COMMAND="(cd $LOCAL/sandbox && tar -xf $TMP_DIR/sandbox.tar.gz)"

# NOTE: proot exec is broken on some Samsung kernels (execve EPERM/ENOSYS),
# and Samsung blocks hardlink() for apps (Ubuntu base has 2 hardlinks).
# So: plain toybox tar first (symlinks are fine), then convert the failed
# hardlinks into copies. No proot needed for extraction.
mkdir -p "$LOCAL/sandbox"
if [ -f "$TMP_DIR/sandbox.tar.gz" ]; then
    (cd "$LOCAL/sandbox" && tar -xf "$TMP_DIR/sandbox.tar.gz" 2>/dev/null || true)
    (cd "$LOCAL/sandbox" || exit 1
    tar -tf "$TMP_DIR/sandbox.tar.gz" 2>/dev/null | grep " link to " | while IFS= read -r line; do
        name="${line% link to *}"
        target="${line##* link to }"
        [ -z "$name" ] || [ -z "$target" ] && continue
        if [ ! -e "$name" ] && [ -f "$target" ]; then
            cp "$target" "$name" 2>/dev/null || true
        elif [ ! -e "$name" ] && [ -f "$(dirname "$name")/$target" ]; then
            cp "$(dirname "$name")/$target" "$name" 2>/dev/null || true
        fi
    done)
fi
if [ ! -e "$LOCAL/sandbox/bin/bash" ] && [ ! -f "$LOCAL/sandbox/usr/bin/bash" ]; then
    if [ "$FDROID" = false ]; then
        $LINKER $LOCAL/bin/proot $ARGS /system/bin/sh -c "$COMMAND"
    else
        $LOCAL/bin/proot $ARGS /system/bin/sh -c "$COMMAND"
    fi
fi


SANDBOX_DIR="$LOCAL/sandbox"

info "Setting up the Ubuntu container…"

# values you want written
nameserver="nameserver 8.8.8.8
nameserver 8.8.4.4"

hosts="127.0.0.1   localhost.localdomain localhost

# IPv6.
::1         localhost.localdomain localhost ip6-localhost ip6-loopback
fe00::0     ip6-localnet
ff00::0     ip6-mcastprefix
ff02::1     ip6-allnodes
ff02::2     ip6-allrouters
ff02::3     ip6-allhosts"

# ensure etc directory exists
mkdir -p "$SANDBOX_DIR/etc"

# write hostname
printf '%s\n' "Xed-Editor" > "$SANDBOX_DIR/etc/hostname"

# write resolv.conf (create file if not exists, then overwrite)
: > "$SANDBOX_DIR/etc/resolv.conf"
printf '%s\n' "$nameserver" > "$SANDBOX_DIR/etc/resolv.conf"

# write hosts
printf '%s\n' "$hosts" > "$SANDBOX_DIR/etc/hosts"

# Patch rootfs bashrc: bare `groups` (sudo hint) falls through to host
# /system/bin/groups which fails under proot on some kernels
if [ -f "$SANDBOX_DIR/etc/bash.bashrc" ]; then
    sed -i 's/case " $(groups) " in/case " $(groups 2>\/dev\/null || id -Gn 2>\/dev\/null || echo) " in/' "$SANDBOX_DIR/etc/bash.bashrc" 2>/dev/null || true
fi

groupFile="$SANDBOX_DIR/etc/group"
aid="$(id -g)"

linesToAdd="
inet:x:3003
everybody:x:9997
android_app:x:20455
android_debug:x:50455
android_cache:x:$((10000 + aid))
android_storage:x:$((40000 + aid))
android_media:x:$((50000 + aid))
android_external_storage:x:1077
"

# create the file if it doesn't exist
[ -f "$groupFile" ] || : > "$groupFile"

existing="$(cat "$groupFile")"

# iterate through lines
echo "$linesToAdd" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    gid="${line##*:}"  # get part after last colon
    case "$existing" in
        *:"$gid"*) : ;;   # already exists → skip
        *) printf '%s\n' "$line" >> "$groupFile" ;;
    esac
done


# Create fips_enabled stub for libgcrypt compatibility
# libgcrypt aborts when /proc/sys/crypto/fips_enabled is unreadable
# (missing on Samsung kernels + proot filter quirks). Stub it as "0".
FIPS_STUB="$LOCAL/fips_enabled"
[ -f "$FIPS_STUB" ] || echo "0" > "$FIPS_STUB" 2>/dev/null || true

# dpkg/apt fixup wrappers are installed by init.sh on every shell start
# (covers both first setup and later app updates).

rm -f "$TMP_DIR"/sandbox.tar.gz
# DO NOT REMOVE THIS FILE JUST DON'T, TRUST ME
touch $LOCAL/.terminal_setup_ok_DO_NOT_REMOVE

# Setup work is done — release the lock now (not at script exit, which would
# only happen when this terminal session closes). Queued sessions can proceed.
rmdir "$SETUP_LOCK" 2>/dev/null || true

if [ $# -gt 0 ]; then
    sh $@
else
    clear
    sh $LOCAL/bin/sandbox
fi
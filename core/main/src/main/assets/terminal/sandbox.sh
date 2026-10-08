#!/bin/sh
# shellcheck disable=SC2034
force_color_prompt=yes

export PROOT_NO_SECCOMP=1
export PROOT_ASSUME_NEW_SECCOMP=1
export PROOT_F2FS_WORKAROUND=1
export PROOT_L2S_DIR="$LOCAL/sandbox/.l2s"
mkdir -p "$PROOT_L2S_DIR" 2>/dev/null || true
if [ -z "$PROOT_TMP_DIR" ]; then
    export PROOT_TMP_DIR="$LOCAL/tmp"
    mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
fi
export UV_THREADPOOL_SIZE=1

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
ARGS="$ARGS -b $EXT_HOME:/home"
ARGS="$ARGS -b $EXT_HOME:/root"
ARGS="$ARGS -b $PRIVATE_DIR"
ARGS="$ARGS -b $LOCAL/stat:/proc/stat"
ARGS="$ARGS -b $LOCAL/vmstat:/proc/vmstat"

# libgcrypt aborts when /proc/sys/crypto/fips_enabled is unreadable
# (missing on Samsung kernels + proot filter quirks). Stub it as "0".
FIPS_STUB="$LOCAL/fips_enabled"
[ -f "$FIPS_STUB" ] || echo "0" > "$FIPS_STUB" 2>/dev/null || true
[ -f "$FIPS_STUB" ] && ARGS="$ARGS -b $FIPS_STUB:/proc/sys/crypto/fips_enabled"

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

if [ ! -d "$LOCAL/sandbox/tmp" ]; then
 mkdir -p "$LOCAL/sandbox/tmp"
 chmod 1777 "$LOCAL/sandbox/tmp"
fi

ARGS="$ARGS -b $LOCAL/sandbox/tmp:/dev/shm"

ARGS="$ARGS -r $LOCAL/sandbox"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

chmod -R +x $LOCAL/bin

if [ "$FDROID" = false ]; then
    if [ $# -gt 0 ]; then
        $LINKER $LOCAL/bin/proot $ARGS /bin/bash --rcfile $LOCAL/bin/init -i -c "$*"
    else
        $LINKER $LOCAL/bin/proot $ARGS /bin/bash --rcfile $LOCAL/bin/init -i
    fi
else
    if [ $# -gt 0 ]; then
        $LOCAL/bin/proot $ARGS /bin/bash --rcfile $LOCAL/bin/init -i -c "$*"
    else
        $LOCAL/bin/proot $ARGS /bin/bash --rcfile $LOCAL/bin/init -i
    fi
fi


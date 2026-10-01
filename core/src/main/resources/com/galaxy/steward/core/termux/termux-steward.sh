# Galaxy Steward - Termux helper.
#
# Sent by the app through Termux's RUN_COMMAND bridge and run as the Termux user:
#   bash -c "<this script>" steward audit [--out FILE]
#   bash -c "<this script>" steward clean [--out FILE] TARGET...
#   bash -c "<this script>" steward packages [--out FILE]
#   bash -c "<this script>" steward pkg-plan [--out FILE] PACKAGE...
#   bash -c "<this script>" steward pkg-remove [--out FILE] [--autoremove] PACKAGE...
#   bash -c "<this script>" steward distro-remove [--out FILE] ROOTFS
#   bash -c "<this script>" steward delete [--out FILE] PATH...
#   bash -c "<this script>" steward prog-remove [--out FILE] npm|pip|cargo NAME...
#   bash -c "<this script>" steward repos [--out FILE]
#   bash -c "<this script>" steward git-gc [--out FILE] REPO...
#   bash -c "<this script>" steward relocate [--out FILE] SHARED_FOLDER
#   bash -c "<this script>" steward relocate-back [--out FILE] TERMUX_FOLDER SHARED_FOLDER
#   bash -c "<this script>" steward keep|unkeep [--out FILE] PATH...
#
# Ported from the Koa Whole-Device Storage Steward v18 (safe_clean, dev_cache_clean,
# deep_termux_known_cache_cleanup, clean_proot_rebuildable_caches, cleanup_git_rebuildables).
# Every path is re-validated right before it is touched: it must sit beneath HOME,
# PREFIX or the Termux app folder without passing through a symlink.
#
# Output: one tab-separated record per line (see TermuxProtocol.kt).

set -u
export LC_ALL=C

MODE="${1:-audit}"
[ "$#" -gt 0 ] && shift
OUT=""
if [ "${1:-}" = "--out" ]; then
  OUT="${2:-}"
  shift 2 2>/dev/null || shift $#
fi

has_controls() {
  case "${1:-}" in
    *$'\n'*|*$'\r'*|*$'\t'*) return 0 ;;
  esac
  return 1
}

if [ -n "$OUT" ]; then
  if has_controls "$OUT" || ! { : > "$OUT"; } 2>/dev/null; then OUT=""; fi
fi
if [ -n "$OUT" ]; then exec 3>"$OUT"; else exec 3>&1; fi

emit() {
  local IFS=$'\t'
  printf '%s\n' "$*" >&3
}

finish() {
  FINISHED=1
  emit E "$1"
  exec 3>&-
  [ -n "$OUT" ] && printf 'O\t%s\n' "$OUT"
  exit "${2:-0}"
}

refuse() {
  emit X refused "$1"
  finish refused 2
}

# What bash itself complains about goes to a file, so a run that stops unexpectedly (a bad array subscript or an
# arithmetic error ends a bash script on the spot) can say why: Z, reason, then E failed. The app shows the reason
# instead of "the output was cut short".
FINISHED=""
ERRLOG="$(mktemp "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/steward-err.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)" || ERRLOG=""
[ -n "$ERRLOG" ] && exec 2>"$ERRLOG"

last_error() {
  [ -n "$ERRLOG" ] && [ -s "$ERRLOG" ] && tail -n 1 -- "$ERRLOG" 2>/dev/null | tr '\t\r\n' '   ' | cut -c1-240
}

on_exit() {
  local st=$? why
  if [ -z "$FINISHED" ]; then
    why="$(last_error)"
    emit Z "${why:-the helper stopped with status $st}"
    emit E failed
  fi
  [ -n "$ERRLOG" ] && rm -f -- "$ERRLOG"
}
trap on_exit EXIT

# In the test fixture only: stops here the way a real bash error does (an empty associative-array key is fatal).
CRASH_AT=""
crash_point() {
  if [ -n "$CRASH_AT" ] && [ "$CRASH_AT" = "$1" ]; then
    local -A boom=()
    boom[""]=1
  fi
}

# Milliseconds since the epoch, for timing the parts of a report.
now_ms() {
  local t="${EPOCHREALTIME:-}"
  if [ -n "$t" ]; then t="${t/./}"; echo "${t:0:${#t}-3}"; else echo "$(( $(date +%s) * 1000 ))"; fi
}

# Y, part, milliseconds: how long a part of a report took, for the run log.
timed() {
  local name="$1" t
  shift
  t="$(now_ms)"
  "$@"
  emit Y "$name" "$(( $(now_ms) - t ))"
}

# Runs an optional part of a report in a subshell, so one that fails can't take the rest with it, and times it.
# Y for the time since the last lap (LAP), for the parts of one long step.
LAP=0
lap() {
  local t
  t="$(now_ms)"
  emit Y "$1" "$(( t - LAP ))"
  LAP="$t"
}

optional() {
  local name="$1" t
  shift
  t="$(now_ms)"
  if ! ( crash_point "$name"; "$@" ); then emit W "$name stopped early: $(last_error)"; fi
  emit Y "$name" "$(( $(now_ms) - t ))"
}

# ---------------------------------------------------------------- context

[ -n "${HOME:-}" ] && [ -n "${PREFIX:-}" ] || refuse "HOME and PREFIX must be set: run this inside Termux"
has_controls "$HOME$PREFIX" && refuse "unsafe environment"
[ -d "$HOME" ] && [ ! -L "$HOME" ] && [ -d "$PREFIX" ] && [ ! -L "$PREFIX" ] || refuse "HOME or PREFIX is missing or a symlink"
FILES="${HOME%/home}"
[ "$FILES" != "$HOME" ] && [ "$PREFIX" = "$FILES/usr" ] || refuse "not a Termux layout"
APPDIR="${FILES%/files}"
# Shared storage, as Termux sees it with storage access: the Android user's own (Termux in Secure Folder, user 150,
# has /data/user/150/com.termux/files and /storage/emulated/150).
SHARED=/storage/emulated/0
case "$FILES" in
  /data/user/[0-9]*/com.termux/files)
    u="${FILES#/data/user/}"
    SHARED="/storage/emulated/${u%%/*}"
    ;;
esac
ARCH="$(uname -m 2>/dev/null)"
# Identical files, folder sketches and proot's hard-link copies are looked at from these sizes up (KiB).
DUP_MIN_KIB=8192
SKETCH_MIN_KIB=65536
L2S_MIN_KIB=16384
if [ "${STEWARD_TERMUX_FIXTURE:-0}" = "1" ]; then
  [ "$(cat -- "$APPDIR/.steward-termux-fixture" 2>/dev/null)" = "DISPOSABLE-FIXTURE" ] || refuse "fixture marker missing"
  SHARED="${STEWARD_FIXTURE_SHARED:-$SHARED}"
  ARCH="${STEWARD_FIXTURE_ARCH:-$ARCH}"
  DUP_MIN_KIB="${STEWARD_FIXTURE_MIN_KIB:-$DUP_MIN_KIB}"
  SKETCH_MIN_KIB="${STEWARD_FIXTURE_MIN_KIB:-$SKETCH_MIN_KIB}"
  L2S_MIN_KIB="${STEWARD_FIXTURE_MIN_KIB:-$L2S_MIN_KIB}"
  # Tests make one named part stop the way a bash error would (see crash_point).
  CRASH_AT="${STEWARD_FIXTURE_CRASH:-}"
else
  case "$FILES" in
    /data/data/com.termux/files|/data/user/[0-9]*/com.termux/files) ;;
    *) refuse "not the Termux app sandbox" ;;
  esac
  uid="$(id -u 2>/dev/null || echo 0)"
  [ "$uid" -ge 10000 ] 2>/dev/null || refuse "must run as the Termux app user, not root or the Android shell"
fi

# ---------------------------------------------------------------- path safety (path_resolves_beneath_v18)

beneath() {
  local p="${1:-}" root="${2:-}" rel root_real p_real
  [ -n "$p" ] && [ -n "$root" ] || return 1
  [ -d "$root" ] && [ ! -L "$root" ] || return 1
  has_controls "$p" && return 1
  case "$p" in "$root"/*) ;; *) return 1 ;; esac
  [ ! -L "$p" ] || return 1
  rel="${p#"$root"/}"
  [ -n "$rel" ] || return 1
  case "/$rel/" in */../*|*/./*|*//*) return 1 ;; esac
  root_real="$(readlink -f -- "$root" 2>/dev/null)" || return 1
  p_real="$(readlink -f -- "$p" 2>/dev/null)" || return 1
  [ -n "$root_real" ] && [ "$p_real" = "$root_real/$rel" ]
}

dir_beneath() { [ -d "${1:-}" ] && beneath "$1" "$2"; }
file_beneath() { [ -f "${1:-}" ] && beneath "$1" "$2"; }

sum_sizes() { awk '{s+=$1; n++} END {printf "%d\t%d\n", s+0, n+0}'; }

# bytes<TAB>files that a clean in MODE would remove from PATH
measure() {
  local mode="$1" p="$2" f s=0 n=0
  case "$mode" in
    debs) find "$p" -xdev -mindepth 1 -maxdepth 1 -type f -name '*.deb' -printf '%s\n' 2>/dev/null | sum_sizes ;;
    aptbin)
      for f in "$p/pkgcache.bin" "$p/srcpkgcache.bin"; do
        [ -f "$f" ] && [ ! -L "$f" ] || continue
        s=$((s + $(stat -c %s -- "$f" 2>/dev/null || echo 0)))
        n=$((n + 1))
      done
      printf '%d\t%d\n' "$s" "$n"
      ;;
    old) find "$p" -xdev -type f -mtime +7 -printf '%s\n' 2>/dev/null | sum_sizes ;;
    oldlog) find "$p" -xdev -type f -name '*.log' -mtime +7 -printf '%s\n' 2>/dev/null | sum_sizes ;;
    pycache) pycache_dirs "$p" | xargs -0 -r -I{} find {} -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
    oldversions) old_versions "$p" | xargs -0 -r -I{} find {} -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
    oldversions-unsure) old_versions "$p" unsure | xargs -0 -r -I{} find {} -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
    oldgradle) old_gradle_dists "$p" | xargs -0 -r -I{} find {} -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
    gomod) find "$p" -xdev -mindepth 1 -maxdepth 1 ! -name cache -print0 2>/dev/null | xargs -0 -r -I{} find {} -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
    *) find "$p" -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
  esac
}

# Versions a self-updating tool (Claude Code) keeps next to the one in use: every entry of P except the newest and the
# ones ~/.local/bin/claude runs. That launcher is a link to one version, or (on Termux, where Claude Code runs through
# glibc) a small script: every version it names stays. Nothing goes when there is no launcher to tell.
old_versions() {
  local p="$1" unsure="${2:-}" launcher="$HOME/.local/bin/claude" used="" named="" newest e v known=1
  if [ -L "$launcher" ]; then
    used="$(readlink -f -- "$launcher" 2>/dev/null)" || known=0
    case "$used" in "$p"/*) ;; *) known=0 ;; esac
  elif [ -f "$launcher" ] && [ "$(stat -c %s -- "$launcher" 2>/dev/null || echo 0)" -le 65536 ]; then
    named=" $(grep -ao 'versions/[A-Za-z0-9._+-]*' -- "$launcher" 2>/dev/null | sed 's#^versions/##' | tr '\n' ' ')"
  else
    known=0
  fi
  # Sure: the launcher says which version runs. Unsure: it doesn't, so only the newest is kept, and you review.
  if [ "$unsure" = unsure ]; then [ "$known" = 0 ] || return 0; else [ "$known" = 1 ] || return 0; fi
  newest="$(find "$p" -mindepth 1 -maxdepth 1 -printf '%T@\t%f\n' 2>/dev/null | sort -rn | head -n 1 | cut -f2)"
  for e in "$p"/*; do
    [ -e "$e" ] && [ ! -L "$e" ] || continue
    v="${e##*/}"
    [ "$v" = "$newest" ] && continue
    case "$used" in "$e"|"$e"/*) continue ;; esac
    case "$named" in *" $v "*) continue ;; esac
    printf '%s\0' "$e"
  done
}

# Python bytecode caches (__pycache__) below P, outside shared storage, Git internals and proot distributions.
# Python ignores a cached .pyc whose source is gone and rebuilds the rest on the next import (PEP 3147).
pycache_dirs() {
  find "$1" -xdev \( -path "$HOME/storage" -o -name .git -o -iname '*-rootfs' -o -iname '*-fs' -o -name installed-rootfs \) -prune \
    -o -type d -name __pycache__ -print0 -prune 2>/dev/null
}

# Gradle versions in ~/.gradle/wrapper/dists that no project's wrapper in the home asks for. The newest always stays,
# so the next build of anything recent starts without a download.
old_gradle_dists() {
  local p="$1" f u d name newest want=" "
  while IFS= read -r -d '' f; do
    u="$(sed -n 's#^distributionUrl=.*/\(gradle-[^/]*\)\.zip.*#\1#p' -- "$f" 2>/dev/null | head -n 1)"
    [ -n "$u" ] && want+="$u "
  done < <(find "$HOME" -xdev -maxdepth 7 \( -path "$HOME/storage" -o -path "$HOME/.gradle" -o -name node_modules -o -name .git \) -prune \
      -o -name gradle-wrapper.properties -print0 2>/dev/null)
  newest="$(find "$p" -mindepth 1 -maxdepth 1 -type d -printf '%T@\t%f\n' 2>/dev/null | sort -rn | head -n 1 | cut -f2)"
  for d in "$p"/*; do
    [ -d "$d" ] && [ ! -L "$d" ] || continue
    name="${d##*/}"
    [ "$name" = "$newest" ] && continue
    case "$want" in *" $name "*) continue ;; esac
    printf '%s\0' "$d"
  done
}

# Caches Chromium, Electron apps (VS Code, Code - OSS) and browsers keep under ~/.config: rebuilt as they are used.
# Only in a folder that is plainly such an app's profile (Local State, Preferences, Local Storage, User, Cookies).
APP_CACHE_FIND=( -name Cache -o -name 'Code Cache' -o -name GPUCache -o -name CachedData -o -name DawnCache -o -name DawnGraphiteCache
  -o -name DawnWebGPUCache -o -name GrShaderCache -o -name ShaderCache -o -name Crashpad -o -name CachedExtensionVSIXs -o -name logs )

app_profile() {
  [ -e "$1/Local State" ] || [ -e "$1/Preferences" ] || [ -d "$1/Local Storage" ] || [ -d "$1/User" ] || [ -e "$1/Cookies" ]
}

# app-cache|app-logs PATH: still one of those folders (logs only go by age).
app_cache_ok() {
  local id="$1" p="$2" name
  case "$p" in "$HOME"/.config/*/*) ;; *) return 1 ;; esac
  name="${p##*/}"
  case "$id:$name" in
    app-logs:logs) ;;
    app-cache:Cache|"app-cache:Code Cache"|app-cache:GPUCache|app-cache:CachedData|app-cache:DawnCache|app-cache:DawnGraphiteCache) ;;
    app-cache:DawnWebGPUCache|app-cache:GrShaderCache|app-cache:ShaderCache|app-cache:Crashpad|app-cache:CachedExtensionVSIXs) ;;
    *) return 1 ;;
  esac
  app_profile "${p%/*}" && dir_beneath "$p" "$HOME"
}

app_caches() {
  local d
  [ -d "$HOME/.config" ] || return 0
  while IFS= read -r -d '' d; do
    has_controls "$d" && continue
    if [ "${d##*/}" = logs ]; then app_cache_ok app-logs "$d" && printf 'app-logs\t%s\0' "$d"
    else app_cache_ok app-cache "$d" && printf 'app-cache\t%s\0' "$d"; fi
  done < <(find "$HOME/.config" -xdev -mindepth 2 -maxdepth 4 -type d \( "${APP_CACHE_FIND[@]}" \) -print0 -prune 2>/dev/null)
}

# crash-log|heap-dump PATH: what a crashed Java program (Gradle, Android Studio's tools) or process left in the home:
# hs_err_pid*.log and replay_pid*.log, heap dumps (.hprof) and core dumps. Only ones older than a day.
crash_dump_kind() {
  local p="$1" n="${1##*/}" hex
  file_beneath "$p" "$HOME" || return 1
  [ -n "$(find "$p" -maxdepth 0 -mtime +0 2>/dev/null)" ] || return 1
  case "$n" in
    hs_err_pid*.log|replay_pid*.log) echo crash-log ;;
    *.hprof) echo heap-dump ;;
    core|core.[0-9]*)
      hex="$(head -c 18 -- "$p" 2>/dev/null | od -An -tx1 -v | tr -d ' \n')"
      case "$hex" in 7f454c46????????????????????????0400) echo heap-dump ;; *) return 1 ;; esac
      ;;
    *) return 1 ;;
  esac
}

# old-snapshot PATH: dated reports, debug dumps and snapshots at the top of the home (Android-Update-Audit-20260928-
# 085258, Layla-Debug-20260928-114347), made once at that time, with nothing in them changed for three days. Not a
# project or repository, nothing hidden, no keys.
snapshot_ok() {
  local p="$1"
  case "$p" in "$HOME"/*/*|"$HOME"/.*) return 1 ;; "$HOME"/*) ;; *) return 1 ;; esac
  [[ "${p##*/}" =~ $SNAPSHOT_DATE ]] || return 1
  [ -e "$p" ] && [ ! -L "$p" ] && beneath "$p" "$HOME" || return 1
  if [ -d "$p" ]; then
    [ -e "$p/.git" ] || [ -e "$p/build.gradle" ] || [ -e "$p/build.gradle.kts" ] || [ -e "$p/package.json" ] || [ -e "$p/Cargo.toml" ] ||
      [ -e "$p/pyproject.toml" ] && return 1
    [ -n "$(find "$p" -xdev -mtime -3 -print -quit 2>/dev/null)" ] && return 1
    [ -n "$(private_key_in "$p")" ] && return 1
  else
    [ -n "$(find "$p" -maxdepth 0 -mtime -3 2>/dev/null)" ] && return 1
    case "${p,,}" in *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.kdbx|*.env) return 1 ;; esac
    # A dated copy of a file that is still there is offered as a copy, once.
    file_copy_ok "$p" && return 1
  fi
  return 0
}

home_snapshots() {
  local p
  while IFS= read -r -d '' p; do
    has_controls "$p" && continue
    snapshot_ok "$p" && printf '%s\0' "$p"
  done < <(find "$HOME" -xdev -mindepth 1 -maxdepth 1 ! -name '.*' \( -type d -o -type f \) -print0 2>/dev/null)
}

# file-copy PATH: a copy made before an edit, next to the file it copies (relay.py.bak-layla-route-20260903-173538,
# x.orig, x.old, x~), in the home outside hidden folders. Only while the file it copies is still there.
copy_original() {
  local f="$1"
  if [[ "$f" =~ ^(.*[^/])\.(bak|backup|orig|old|prev|save|before)([-._][^/]*)?$ ]]; then printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "$f" == *[^/]~ ]]; then printf '%s' "${f%\~}"
  else return 1; fi
}

file_copy_ok() {
  local f="$1" o
  file_beneath "$f" "$HOME" || return 1
  case "${f#"$HOME"/}" in .*|*/.*) return 1 ;; esac
  o="$(copy_original "$f")" || return 1
  [ -f "$o" ] && [ "$o" != "$f" ] || return 1
  case "${f,,}" in *.jks*|*.keystore*|*.p12*|*.pfx*|*.pem*|*.key*|*.kdbx*|*.env*) return 1 ;; esac
  return 0
}

file_copies() {
  local f
  while IFS= read -r -d '' f; do
    has_controls "$f" && continue
    file_copy_ok "$f" && printf '%s\0' "$f"
  done < <(find "$HOME" -xdev -mindepth 1 -maxdepth 4 \( -path "$HOME/storage" -o -name node_modules -o -name '.*' \) -prune \
      -o -type f -regextype posix-extended \( -iregex '.*\.(bak|backup|orig|old|prev|save|before)([-._][^/]*)?' -o -name '*~' \) -print0 2>/dev/null)
}

crash_dumps() {
  local f k
  while IFS= read -r -d '' f; do
    has_controls "$f" && continue
    k="$(crash_dump_kind "$f")" && printf '%s\t%s\0' "$k" "$f"
  done < <(find "$HOME" -xdev -maxdepth 5 \( -path "$HOME/storage" -o -name .git -o -name node_modules -o -iname '*-rootfs' \) -prune \
      -o -type f \( -name 'hs_err_pid*.log' -o -name 'replay_pid*.log' -o -name '*.hprof' -o -name core -o -name 'core.[0-9]*' \) -print0 2>/dev/null)
}

# ---------------------------------------------------------------- known targets

FIXED_IDS="apt-archives apt-pkgcache termux-tmp var-tmp termux-var-log proot-dlcache trash npm-logs termux-app-cache npm-cache npx-cache pip-cache uv-cache poetry-cache pycache yarn-cache yarn-berry-cache go-build go-mod-download cargo-registry-cache cargo-registry-src cargo-registry-index cargo-git-db cargo-git-checkouts rustup-downloads rustup-tmp bun-cache android-cache gradle-daemon-logs gradle-caches claude-versions claude-versions-unsure koa-archives apt-lists home-node-modules cpan-build cpan-sources go-mod m2-repository gradle-wrapper-old code-server-vsix sdk-temp sdk-system-images sdk-emulator konan"

# The Android SDK, wherever it is: ANDROID_HOME, ANDROID_SDK_ROOT, or where people put it in Termux.
SDK_DIR=""
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/Android/sdk" "$PREFIX/opt/android-sdk" "$PREFIX/share/android-sdk"; do
  [ -n "$d" ] && [ -d "$d" ] && [ ! -L "$d" ] || continue
  if beneath "$d" "$HOME" || beneath "$d" "$PREFIX"; then SDK_DIR="$d"; break; fi
done
unset d

# A program for an x86 PC on an ARM phone that has no x86 emulator (box64, qemu) to start it.
arm_phone() {
  case "$ARCH" in aarch64|arm*) ;; *) return 1 ;; esac
  [ ! -e "$PREFIX/bin/box64" ] && [ ! -e "$PREFIX/bin/qemu-x86_64" ]
}

elf_foreign() {
  local hex
  arm_phone && [ -f "${1:-}" ] || return 1
  hex="$(head -c 20 -- "$1" 2>/dev/null | od -An -tx1 -v | tr -d ' \n')"
  case "$hex" in 7f454c46*) case "${hex:36:4}" in 3e00|0300) return 0 ;; esac ;; esac
  return 1
}

# id -> path|allowed-root|mode
target_info() {
  case "$1" in
    apt-archives) echo "$PREFIX/var/cache/apt/archives|$PREFIX|debs" ;;
    apt-pkgcache) echo "$PREFIX/var/cache/apt|$PREFIX|aptbin" ;;
    apt-lists) echo "$PREFIX/var/lib/apt/lists|$PREFIX|contents" ;;
    termux-tmp) echo "$PREFIX/tmp|$PREFIX|old" ;;
    var-tmp) echo "$PREFIX/var/tmp|$PREFIX|old" ;;
    termux-var-log) echo "$PREFIX/var/log|$PREFIX|old" ;;
    proot-dlcache) echo "$PREFIX/var/lib/proot-distro/dlcache|$PREFIX|contents" ;;
    trash) echo "$HOME/.local/share/Trash|$HOME|contents" ;;
    npm-logs) echo "$HOME/.npm/_logs|$HOME|old" ;;
    termux-app-cache) echo "$APPDIR/cache|$APPDIR|contents" ;;
    npm-cache) echo "$HOME/.npm/_cacache|$HOME|contents" ;;
    npx-cache) echo "$HOME/.npm/_npx|$HOME|contents" ;;
    pip-cache) echo "$HOME/.cache/pip|$HOME|contents" ;;
    uv-cache) echo "$HOME/.cache/uv|$HOME|contents" ;;
    poetry-cache) echo "$HOME/.cache/pypoetry|$HOME|contents" ;;
    pycache) echo "$HOME|$FILES|pycache" ;;
    yarn-cache) echo "$HOME/.cache/yarn|$HOME|contents" ;;
    yarn-berry-cache) echo "$HOME/.yarn/berry/cache|$HOME|contents" ;;
    go-build) echo "$HOME/.cache/go-build|$HOME|contents" ;;
    go-mod-download) echo "$HOME/go/pkg/mod/cache/download|$HOME|contents-rw" ;;
    cargo-registry-cache) echo "$HOME/.cargo/registry/cache|$HOME|contents" ;;
    cargo-registry-src) echo "$HOME/.cargo/registry/src|$HOME|contents" ;;
    cargo-registry-index) echo "$HOME/.cargo/registry/index|$HOME|contents" ;;
    cargo-git-db) echo "$HOME/.cargo/git/db|$HOME|contents" ;;
    cargo-git-checkouts) echo "$HOME/.cargo/git/checkouts|$HOME|contents" ;;
    rustup-downloads) echo "$HOME/.rustup/downloads|$HOME|contents" ;;
    rustup-tmp) echo "$HOME/.rustup/tmp|$HOME|contents" ;;
    bun-cache) echo "$HOME/.bun/install/cache|$HOME|contents" ;;
    android-cache) echo "$HOME/.android/cache|$HOME|contents" ;;
    gradle-daemon-logs) echo "$HOME/.gradle/daemon|$HOME|oldlog" ;;
    claude-versions) echo "$HOME/.local/share/claude/versions|$HOME|oldversions" ;;
    claude-versions-unsure) echo "$HOME/.local/share/claude/versions|$HOME|oldversions-unsure" ;;
    koa-archives) echo "$HOME/.storage-autopilot-archives|$HOME|contents" ;;
    gradle-caches) echo "$HOME/.gradle/caches|$HOME|contents" ;;
    home-node-modules) echo "$HOME/node_modules|$HOME|contents" ;;
    cpan-build) echo "$HOME/.cpan/build|$HOME|contents" ;;
    cpan-sources) echo "$HOME/.cpan/sources|$HOME|contents" ;;
    go-mod) echo "$HOME/go/pkg/mod|$HOME|gomod" ;;
    m2-repository) echo "$HOME/.m2/repository|$HOME|contents" ;;
    gradle-wrapper-old) echo "$HOME/.gradle/wrapper/dists|$HOME|oldgradle" ;;
    code-server-vsix) echo "$HOME/.local/share/code-server/CachedExtensionVSIXs|$HOME|contents" ;;
    sdk-temp) [ -n "$SDK_DIR" ] || return 1; echo "$SDK_DIR/.temp|$FILES|contents" ;;
    # The emulator can't run on a phone: neither its images nor, when it is built for x86 PCs, the emulator itself.
    sdk-system-images) [ -n "$SDK_DIR" ] && arm_phone || return 1; echo "$SDK_DIR/system-images|$FILES|contents" ;;
    sdk-emulator) [ -n "$SDK_DIR" ] && elf_foreign "$SDK_DIR/emulator/emulator" || return 1; echo "$SDK_DIR/emulator|$FILES|contents" ;;
    # Kotlin/Native's compilers only run on PCs: what Gradle downloaded for them is dead weight on an ARM phone.
    konan) arm_phone && compgen -G "$HOME/.konan/*x86_64*" > /dev/null || return 1; echo "$HOME/.konan|$HOME|contents" ;;
    *) return 1 ;;
  esac
}

KNOWN_DOT_CACHE="pip uv pypoetry go-build yarn"

# ---------------------------------------------------------------- proot distributions (rootfs_path_allowed_v18)

rootfs_allowed() {
  local r="${1:-}" base rel b
  has_controls "$r" && return 1
  dir_beneath "$r" "$HOME" || dir_beneath "$r" "$PREFIX" || return 1
  for base in "$PREFIX/var/lib/proot-distro" "$HOME/.proot-distro" "$HOME/proot-distro"; do
    case "$r" in "$base"/*)
      rel="${r#"$base"/}"
      case "$rel" in
        installed-rootfs/*/*) ;;
        installed-rootfs/*) return 0 ;;
        containers/*/rootfs) case "$rel" in containers/*/*/rootfs) ;; *) return 0 ;; esac ;;
      esac
      ;;
    esac
  done
  case "$r" in "$HOME"/*)
    rel="${r#"$HOME"/}"
    case "$rel" in */*/*|storage/*|workspace/*|node_modules/*) return 1 ;; esac
    b="$(printf '%s' "${r##*/}" | tr '[:upper:]' '[:lower:]')"
    # The name alone isn't enough (~/node_modules/graceful-fs matched it on a real phone): it must look like a Linux root.
    case "$b" in *-rootfs|*-fs) dir_beneath "$r/etc" "$r" && dir_beneath "$r/usr" "$r" && return 0 ;; esac
    ;;
  esac
  return 1
}

list_rootfs() {
  local base r
  for base in "$PREFIX/var/lib/proot-distro" "$HOME/.proot-distro" "$HOME/proot-distro"; do
    for r in "$base/installed-rootfs"/* "$base/containers"/*/rootfs; do
      [ -d "$r" ] && [ ! -L "$r" ] && printf '%s\0' "$r"
    done
  done
  find "$HOME" -xdev -mindepth 1 -maxdepth 2 \( -path "$HOME/storage" -o -path "$HOME/workspace" -o -name node_modules \) -prune -o \
    -type d \( -iname '*-rootfs' -o -iname '*-fs' \) -print0 2>/dev/null
}

proot_running() {
  pgrep -f '(^|/)(proot|proot-distro)( |$)' >/dev/null 2>&1
}

PROOT_PKG_CACHES="var/cache/apt/archives var/lib/apt/lists var/cache/dnf var/cache/yum var/cache/pacman/pkg var/cache/apk"

proot_cache_suffix_ok() {
  local s="$1" c
  for c in $PROOT_PKG_CACHES; do [ "$s" = "$c" ] && return 0; done
  [[ "$s" =~ ^(root|home/[^/]+)/(\.cache/(pip|uv|yarn|go-build)|\.npm/(_cacache|_npx)|\.cargo/(registry/(cache|src|index)|git/(db|checkouts)))$ ]]
}

# Prints the rootfs that contains PATH (and passes rootfs_allowed), or fails.
rootfs_of() {
  local p="$1" r
  while IFS= read -r -d '' r; do
    case "$p" in "$r"/*) rootfs_allowed "$r" && { printf '%s' "$r"; return 0; } ;; esac
  done < <(list_rootfs)
  return 1
}

# ---------------------------------------------------------------- project build outputs (cleanup_git_rebuildables)

GEN_NAMES="build node_modules target .gradle .cxx .externalNativeBuild .pytest_cache .mypy_cache .ruff_cache coverage .next .turbo .venv venv"

is_gen_name() {
  local n
  for n in $GEN_NAMES; do [ "$1" = "$n" ] && return 0; done
  return 1
}

repo_allowed() {
  local repo="$1" rel
  has_controls "$repo" && return 1
  dir_beneath "$repo" "$HOME" || return 1
  rel="${repo#"$HOME"/}"
  case "$rel" in storage|storage/*|.*) return 1 ;; esac
  case "/$rel/" in */.cache/*|*/.local/*|*/.config/*|*/.npm/*|*/.cargo/*|*/.gradle/*|*/.bun/*) return 1 ;; esac
  if [ -d "$repo/.git" ]; then dir_beneath "$repo/.git" "$repo"
  elif [ -f "$repo/.git" ]; then file_beneath "$repo/.git" "$repo"
  else return 1
  fi
}

has_credentials() {
  find "$1" -xdev -maxdepth 6 -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pem' \
    -o -name '*.key' -o -name '.env' -o -name '.env.*' -o -name 'id_rsa*' -o -name 'id_ed25519*' -o -name '*.kdbx' \) \
    -print -quit 2>/dev/null | grep -q .
}

# Generated directory D in REPO that Git ignores, tracks nothing in, and holds no credentials.
build_ok() {
  local repo="$1" d="$2" name rel
  dir_beneath "$d" "$repo" || return 1
  name="${d##*/}"
  is_gen_name "$name" || return 1
  case "$name" in .venv|venv)
    [ -f "$repo/pyproject.toml" ] || [ -f "$repo/requirements.txt" ] || [ -f "$repo/Pipfile" ] || [ -f "$repo/environment.yml" ] || return 1 ;;
  esac
  rel="${d#"$repo"/}"
  git -C "$repo" check-ignore -q -- "$rel" 2>/dev/null || return 1
  git -C "$repo" ls-files -- "$rel" 2>/dev/null | grep -q . && return 1
  has_credentials "$d" && return 1
  return 0
}

repo_of() {
  local r
  r="$(dirname -- "$1")"
  while [ "$r" != "$HOME" ] && [ "$r" != "/" ] && [ -n "$r" ]; do
    [ -e "$r/.git" ] && { printf '%s' "$r"; return 0; }
    r="$(dirname -- "$r")"
  done
  return 1
}

# ---------------------------------------------------------------- leftovers that aren't caches

# A decompiled app (apktool's apktool.yml, or jadx's resources/AndroidManifest.xml next to sources/) in the home: its
# APK rebuilds it in minutes. Not inside a Git project, where decompiled code is often the work itself, and not with a
# private key in it.
decompiled_ok() {
  local d="$1" rel
  dir_beneath "$d" "$HOME" || return 1
  rel="${d#"$HOME"/}"
  case "$rel" in storage|storage/*|.*|*/.*) return 1 ;; esac
  [ -f "$d/apktool.yml" ] || { [ -f "$d/resources/AndroidManifest.xml" ] && [ -d "$d/sources" ]; } || return 1
  [ -e "$d/.git" ] && return 1
  repo_of "$d/x" > /dev/null && return 1
  [ -z "$(private_key_in "$d" app)" ]
}

decompiled_dirs() {
  local f d
  while IFS= read -r -d '' f; do
    case "$f" in
      */apktool.yml) d="${f%/apktool.yml}" ;;
      */resources/AndroidManifest.xml) d="${f%/resources/AndroidManifest.xml}" ;;
      *) continue ;;
    esac
    decompiled_ok "$d" && printf '%s\0' "$d"
  done < <(find "$HOME" -xdev -mindepth 2 -maxdepth 4 \( -path "$HOME/storage" -o -path "$HOME/.*" -o -name node_modules \
      -o -name .git -o -name sources -o -name smali -o -name 'smali_*' \) -prune -o \
      -type f \( -name apktool.yml -o -path '*/resources/AndroidManifest.xml' \) -print0 2>/dev/null) | sort -zu
}

# An APK directly in the home or one folder down: an installer you downloaded or built (the app says which installed
# app it looks like).
home_apk_ok() {
  local f="$1" rel
  file_beneath "$f" "$HOME" || return 1
  rel="${f#"$HOME"/}"
  case "$rel" in storage/*|.*|*/.*|*/*/*) return 1 ;; esac
  case "$f" in *.apk) return 0 ;; esac
  return 1
}

home_apks() {
  local f
  while IFS= read -r -d '' f; do
    home_apk_ok "$f" && printf '%s\0' "$f"
  done < <(find "$HOME" -xdev -mindepth 1 -maxdepth 2 \( -path "$HOME/storage" -o -path "$HOME/.*" \) -prune -o \
      -type f -name '*.apk' -size +1M -print0 2>/dev/null)
}

# proot's link2symlink keeps each hard-linked file once, in ROOTFS/.l2s, and points every name at it with symlinks.
# Git renames its temporary packs after a download, so a live pack can sit there as .l2s.tmp_pack_XYZ0001.0001: the
# name says nothing. Only a stored file no symlink in the distribution points at (directly, or through its link-count
# name) is a leftover.
l2s_referenced() {
  local r="$1" base="${2##*/}"
  find "$r" -xdev -type l -printf '%l\n' 2>/dev/null | grep -F '.l2s.' | sed 's#.*/##' | grep -qxF -e "$base" -e "${base%.*}"
}

l2s_orphans() {
  local r="$1" f
  local -a big=()
  [ -d "$r/.l2s" ] && [ ! -L "$r/.l2s" ] || return 0
  for f in "${!BIG[@]}"; do
    case "$f" in "$r/.l2s/"*) [ -f "$f" ] && [ ! -L "$f" ] && [ "${BIG[$f]}" -ge "$L2S_MIN_KIB" ] && big+=("$f") ;; esac
  done
  [ "${#big[@]}" -gt 0 ] || return 0
  declare -A refs=()
  while IFS= read -r f; do [ -n "$f" ] && refs["$f"]=1; done < <(find "$r" -xdev -type l -printf '%l\n' 2>/dev/null | grep -F '.l2s.' | sed 's#.*/##')
  for f in "${big[@]}"; do
    [ -n "${refs[${f##*/}]:-}" ] && continue
    [ -n "${refs[$(basename -- "${f%.*}")]:-}" ] && continue
    printf '%s\0' "$f"
  done
}

# An x86-64 emulator (box64, qemu-user) lets x86-64 programs run, so their toolchains are in use.
x86_emulator() {
  local root="$1" b
  for b in "$root/usr/bin/box64" "$root/usr/local/bin/box64" "$root/usr/bin/qemu-x86_64" "$root/usr/bin/qemu-x86_64-static" \
    "$PREFIX/bin/box64" "$PREFIX/bin/qemu-x86_64"; do
    [ -e "$b" ] && return 0
  done
  return 1
}

# Only foreign hosts in NDK/toolchains/llvm/prebuilt: Google builds the NDK for x86-64 PCs only, and its compilers
# can't run on the phone's ARM CPU.
foreign_ndk_ok() {
  local ndk="$1" hosts
  case "$ARCH" in aarch64|arm*) ;; *) return 1 ;; esac
  dir_beneath "$ndk/toolchains/llvm/prebuilt" "$ndk" || return 1
  hosts="$(find "$ndk/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null)"
  [ -n "$hosts" ] || return 1
  ! printf '%s\n' "$hosts" | grep -qvE '^(linux-x86_64|darwin-x86_64|darwin-arm64|windows-x86_64|windows)$'
}

foreign_ndks() {
  local root="$1" d
  x86_emulator "$root" && return 0
  while IFS= read -r -d '' d; do
    d="${d%/toolchains/llvm/prebuilt}"
    foreign_ndk_ok "$d" && printf '%s\0' "$d"
  done < <(find "$root" -xdev -mindepth 3 -maxdepth 7 \( -name .git -o -name node_modules -o -path "$root/storage" \
      -o -path "$root/proc" -o -path "$root/sys" -o -path "$root/dev" -o -path "$root/usr/lib" -o -path "$root/usr/share" \
      -o -path "$root/.l2s" \) -prune -o -type d -path '*/toolchains/llvm/prebuilt' -print0 -prune 2>/dev/null)
}

# Files of 8 MiB or more that share a size, hashed: identical copies (hard links counted once). Report only; the
# browser deletes. At most 6 GiB is read.
dup_large_files() {
  local f s ino sum budget=$((6 * 1024 * 1024 * 1024))
  declare -A by=() seen=() sizes=()
  for f in "${!BIG[@]}"; do
    [ "${BIG[$f]}" -ge "$DUP_MIN_KIB" ] && [ -f "$f" ] && [ ! -L "$f" ] || continue
    s="$(stat -c '%s:%i' -- "$f" 2>/dev/null)" || continue
    ino="${s#*:}"; s="${s%%:*}"
    [ -n "$ino" ] && [ -n "$s" ] || continue
    [ -n "${seen[$ino]:-}" ] && continue
    seen["$ino"]=1
    by["$s"]+="$f"$'\n'
    sizes["$s"]=$(( ${sizes[$s]:-0} + 1 ))
  done
  for s in "${!by[@]}"; do
    [ "${sizes[$s]}" -ge 2 ] || continue
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      [ "$budget" -ge "$s" ] || return 0
      budget=$((budget - s))
      sum="$(sha256sum -- "$f" 2>/dev/null)" || continue
      [ -n "$sum" ] && emit Q "$s" "${sum:0:64}" "$f"
    done <<< "${by[$s]}"
  done
}

# A bottom-64 sketch of a folder: the 64 smallest hashes of its "relative path<TAB>size" lines. Two folders' sketches
# estimate how much of their content matches by name and size (the app does the same for shared storage, with the
# same hash, so folders can be compared across the two).
# The string hash is polynomial, so similar names get similar values; two multiplications scatter them, as MinHash
# needs. mulmod splits the multiplier so every product stays below 2^53, where awk's numbers are exact.
SKETCH_AWK='function mulmod(x, m,    hi, lo) { hi = int(m / 65536); lo = m % 65536; return ((x * hi) % P * 65536 + x * lo) % P }
BEGIN { P = 4294967291; for (i = 1; i < 256; i++) ord[sprintf("%c", i)] = i }
{ h = 0; n = length($0); for (i = 1; i <= n; i++) h = (h * 31 + ord[substr($0, i, 1)]) % P
  h = mulmod(h + 1, 2654435761); h = (mulmod(h, h) + h) % P; printf "%.0f\n", h }'

sketch_dirs() {
  local p rest k r chosen=$'\n' n=0 skip
  local -a distros
  mapfile -d '' -t distros < <(list_rootfs)
  for p in "${!BIG[@]}"; do
    [ "${BIG[$p]}" -ge "$SKETCH_MIN_KIB" ] && [ -d "$p" ] && [ ! -L "$p" ] || continue
    case "$p" in
      "$HOME"/*) rest="${p#"$HOME"/}" ;;
      "$PREFIX"/opt/*|"$PREFIX"/lib/node_modules/*) rest="${p#"$PREFIX"/}" ;;
      *) continue ;;
    esac
    case "$rest" in storage|storage/*|*/*/*/*|*/.git|*/.git/*) continue ;; esac
    skip=""
    for r in "${distros[@]}"; do case "$p" in "$r"|"$r"/*) skip=1; break ;; esac; done
    [ -n "$skip" ] && continue
    printf '%s\t%s\n' "${BIG[$p]}" "$p"
  done | sort -t "$(printf '\t')" -k1,1nr | while IFS=$'\t' read -r k p; do
    # Largest first; a folder inside one already chosen would only hash the same files again (android-sdk, its ndk,
    # and that ndk's version were three passes over one tree).
    skip=""
    while IFS= read -r r; do [ -n "$r" ] && case "$p" in "$r"/*) skip=1; break ;; esac; done <<< "$chosen"
    [ -n "$skip" ] && continue
    chosen+="$p"$'\n'
    printf '%s\t%s\n' "$k" "$p"
    n=$((n + 1))
    [ "$n" -ge 25 ] && break
  done | {
    # Four folders at a time: hashing every file name in awk is the slow part, and it is all CPU.
    while IFS=$'\t' read -r k p; do
      while [ "$(jobs -rp | wc -l)" -ge 4 ]; do wait -n 2>/dev/null || break; done
      sketch_one "$k" "$p" &
    done
    wait
  }
}

sketch_one() {
  local k="$1" p="$2" tmp n sk
  tmp="$(mktemp "${TMPDIR:-$PREFIX/tmp}/steward-sketch.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)" || return 0
  find "$p" -xdev -type f -printf '%P\t%s\n' 2>/dev/null | head -n 300000 > "$tmp"
  n="$(wc -l < "$tmp")"
  if [ "${n:-0}" -ge 20 ]; then
    sk="$(awk "$SKETCH_AWK" "$tmp" | sort -n -u | head -n 64 | paste -sd, -)"
    emit H "$n" "$(( k * 1024 ))" "$sk" "$p"
  fi
  rm -f -- "$tmp"
}

# F, CPU, bytes, path: big files (8 MiB or more) that are programs for another processor, from their first bytes (ELF
# e_machine x86-64 or x86 on an ARM phone, or a Windows PE). They can't run here without an emulator; box64 or qemu
# in Termux or a distribution means they might, and then nothing is reported.
foreign_binaries() {
  local f hex arch r
  case "$ARCH" in aarch64|arm*) ;; *) return 0 ;; esac
  [ -e "$PREFIX/bin/box64" ] || [ -e "$PREFIX/bin/qemu-x86_64" ] && return 0
  for f in "${!BIG[@]}"; do
    [ "${BIG[$f]}" -ge "$DUP_MIN_KIB" ] && [ -f "$f" ] && [ ! -L "$f" ] || continue
    hex="$(od -An -tx1 -v -N20 -- "$f" 2>/dev/null)"
    hex="${hex//[$' \n']/}"
    arch=""
    case "$hex" in
      7f454c46*)
        case "${hex:36:4}" in 3e00) arch=x86-64 ;; 0300) arch=x86 ;; esac
        ;;
      4d5a*) case "$f" in *.exe|*.dll|*.EXE|*.DLL) arch=windows ;; esac ;;
    esac
    [ -n "$arch" ] || continue
    # Inside a distribution with its own x86-64 emulator they may be in use.
    r="$(rootfs_of "$f" 2>/dev/null)" && x86_emulator "$r" && continue
    emit F "$arch" "$(stat -c %s -- "$f" 2>/dev/null || echo $(( ${BIG[$f]} * 1024 )))" "$f"
  done
}

# O, number of packages, up to three of them, path: every file and folder of the size map under $PREFIX that packages
# installed or hold files in, so the browser can lock them the way delete refuses them (one pass over dpkg's lists).
# What no package touched at all goes on to prefix_leftovers.
emit_owners() {
  local info="$PREFIX/var/lib/dpkg/info" tmp p line
  local -a unowned=()
  [ -d "$info" ] || return 0
  tmp="$(mktemp "${TMPDIR:-$PREFIX/tmp}/steward-owners.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)" || return 0
  for p in "${!BIG[@]}"; do
    case "$p" in "$PREFIX"/var/lib/proot-distro|"$PREFIX"/var/lib/proot-distro/*) ;; "$PREFIX"/*) printf '%s\n' "$p" ;; esac
  done > "$tmp"
  while IFS= read -r line; do
    case "$line" in
      O$'\t'*) printf '%s\n' "$line" >&3 ;;
      N$'\t'*) unowned+=("${line#N$'\t'}") ;;
    esac
  done < <(awk -v prefix="$PREFIX" 'FNR == NR { want[$0] = 1; next }
    FNR == 1 { pkg = FILENAME; sub(/.*\//, "", pkg); sub(/\.list$/, "", pkg); sub(/:.*/, "", pkg) }
    {
      p = $0
      while (length(p) > length(prefix)) {
        if ((p in want) && !((p, pkg) in seen)) {
          seen[p, pkg] = 1
          n[p]++
          if (n[p] <= 3) names[p] = (n[p] == 1) ? pkg : names[p] "," pkg
        }
        sub(/\/[^\/]*$/, "", p)
      }
    }
    END {
      for (p in n) printf "O\t%d\t%s\t%s\n", n[p], names[p], p
      # Only folders one level into opt, share, lib, libexec or include can be leftovers: the rest (thousands of npm
      # globals and caches no package lists) would only slow prefix_leftovers down (98 s on the 9-30 phone).
      for (p in want) if (!(p in n)) {
        rel = substr(p, length(prefix) + 2)
        if (rel ~ /^(opt|share|lib|libexec|include)\/[^\/]+$/) printf "N\t%s\n", p
      }
    }' "$tmp" "$info"/*.list 2>/dev/null)
  rm -f -- "$tmp"
  [ "${#unowned[@]}" -gt 0 ] && prefix_leftovers "${unowned[@]}"
  return 0
}

# A folder in $PREFIX the steward may offer as a leftover: one level into opt, share, lib, libexec or include, and none
# of what package managers other than dpkg keep there (npm's globals are listed as programs).
prefix_leftover_ok() {
  local rel="${1#"$PREFIX"/}"
  case "$rel" in
    opt/*/*|share/*/*|lib/*/*|libexec/*/*|include/*/*) return 1 ;;
    opt/?*|share/?*|lib/?*|libexec/?*|include/?*) ;;
    *) return 1 ;;
  esac
  case "$rel" in lib/node_modules|lib/jvm|lib/proot-distro|share/doc|share/man|share/terminfo) return 1 ;; esac
  dir_beneath "$1" "$PREFIX"
}

# T prefix-unowned|old-python, bytes, files, path, last run, commands: folders of 8 MiB or more in $PREFIX that no
# package installed anything in (put there by hand, or left by an uninstall), and the pip packages of a Python that
# isn't installed any more. With the commands in $PREFIX/bin that lead into each, and when you last ran one.
prefix_leftovers() {
  local p rel id b target cmds last c
  local -a list
  declare -A into=()
  local -a links targets
  load_history
  # Every link in bin resolved in one go (a readlink per link was a fork for each of thousands of commands).
  mapfile -d '' -t links < <(find "$PREFIX/bin" -mindepth 1 -maxdepth 1 -type l -print0 2>/dev/null)
  if [ "${#links[@]}" -gt 0 ]; then
    mapfile -d '' -t targets < <(printf '%s\0' "${links[@]}" | xargs -0 -r realpath -m -z -- 2>/dev/null)
  fi
  for b in "${!links[@]}"; do
    target="${targets[$b]:-}"
    [ -n "$target" ] || continue
    for p in "$@"; do
      [ -n "$p" ] || continue
      case "$target" in "$p"/*) into["$p"]+="${into[$p]:+,}${links[$b]##*/}" ;; esac
    done
  done
  for p in "$@"; do
    [ -n "$p" ] && [ -d "$p" ] && [ ! -L "$p" ] && [ "${BIG[$p]:-0}" -ge 8192 ] || continue
    prefix_leftover_ok "$p" || continue
    rel="${p#"$PREFIX"/}"
    id=prefix-unowned
    if [[ "$rel" =~ ^lib/python([0-9]+\.[0-9]+)$ ]]; then
      # The installed Python's own folder belongs to its package; pip's additions there are listed as programs.
      [ -e "$PREFIX/bin/python${BASH_REMATCH[1]}" ] && continue
      id=old-python
    fi
    cmds="${into[$p]:-}"
    last=0
    IFS=, read -r -a list <<< "$cmds"
    for c in "${list[@]}"; do [ -n "$c" ] && [ "${CMD_LAST[$c]:-0}" -gt "$last" ] && last="${CMD_LAST[$c]}"; done
    emit T "$id" "$(( ${BIG[$p]} * 1024 ))" "$(find "$p" -xdev -type f 2>/dev/null | wc -l)" "$p" "$last" "$(printf '%s' "$cmds" | cut -d, -f1-8)"
  done
}

# ---------------------------------------------------------------- kept: what Termux runs for you and for other apps

# Nothing kept is cleaned, deleted, moved out or suggested for removal, and neither is what it runs with:
#  - anything named after Layla, a Sidekick, a relay or a mini app: Layla's Sidekick mini apps talk to relays that run
#    in Termux, started by another app through RUN_COMMAND, so they never show up in shell history;
#  - what Termux starts by itself (Termux:Boot scripts, enabled termux-services services, Termux:Widget shortcuts,
#    Termux:Tasker tasks, cron jobs) and every file and folder those name;
#  - what runs right now, other than a plain shell: its program, the script it runs and the folder it runs in;
#  - what you listed in ~/.config/galaxy-steward/keep (one path per line; "Always keep" in the app adds to it).
# KEPT: path -> why. KEPT_CMDS: command -> why; the packages that provide those commands stay installed.
declare -A KEPT=() KEPT_CMDS=()
KEEP_LOADED=""
KEEP_FILE="$HOME/.config/galaxy-steward/keep"
KEEP_NAMES='layla|sidekick|relay|mini-?apps?'
MARKER='Galaxy Steward - Termux helper'
NAMED="named like Layla's Sidekick relays"
# A date in a name (20260928, 2026-09-28, 20260928-114347): a snapshot, report or backup made at that time.
SNAPSHOT_DATE='(^|[^0-9])20[0-9][0-9]-?[01][0-9]-?[0-3][0-9]([^0-9]|$)'
# What a copy made before an edit is called: relay.py.bak-x, relay.py.backup.x, relay.py.before-x, x.orig, x.old, x~.
COPY_MARK='\.(bak|backup|orig|old|prev|save|before)([-._]|$)|~$'

# What a kept PATH stands for. In the home: its project (the nearest folder up with a package.json, .git,
# pyproject.toml, requirements.txt, Cargo.toml or go.mod), else its own folder; in a hidden folder of the home (a
# tool's own, like ~/.cargo) just the path itself, so the tool's caches can still go. In $PREFIX: the add-on it
# belongs to (an npm or pip package, a folder in opt, share, lib or libexec, a service). Never the home or $PREFIX.
keep_unit() {
  local p="$1" d rel
  case "$p" in
    "$HOME"/*)
      if [ -d "$p" ] && [ ! -L "$p" ]; then d="$p"; else d="${p%/*}"; fi
      while [ "$d" != "$HOME" ] && [ -n "$d" ]; do
        if [ -e "$d/package.json" ] || [ -e "$d/.git" ] || [ -e "$d/pyproject.toml" ] || [ -e "$d/requirements.txt" ] ||
          [ -e "$d/Cargo.toml" ] || [ -e "$d/go.mod" ]; then
          printf '%s' "$d"
          return 0
        fi
        d="${d%/*}"
      done
      rel="${p#"$HOME"/}"
      case "$rel" in
        .*) printf '%s' "$p" ;;
        */*) if [ -d "$p" ] && [ ! -L "$p" ]; then printf '%s' "$p"; else printf '%s' "${p%/*}"; fi ;;
        *) printf '%s' "$p" ;;
      esac
      ;;
    "$PREFIX"/*)
      rel="${p#"$PREFIX"/}"
      if [[ "$rel" =~ ^(lib/node_modules/(@[^/]+/)?[^/]+) ]] || [[ "$rel" =~ ^(lib/python[0-9.]+/site-packages/[^/]+) ]] ||
        [[ "$rel" =~ ^((opt|share|libexec|var/service)/[^/]+) ]] || [[ "$rel" =~ ^(lib/[^/]+)/ ]]; then
        printf '%s' "$PREFIX/${BASH_REMATCH[1]}"
      else
        printf '%s' "$p"
      fi
      ;;
    *) printf '%s' "$p" ;;
  esac
}

# keep_add PATH WHY [exact]: PATH (or, without exact, what it stands for) stays. A link keeps what it leads to as well.
keep_add() {
  local p="${1:-}" why="$2" how="${3:-}" u t
  [ -n "$p" ] && ! has_controls "$p" || return 0
  p="$(realpath -m -s -- "$p" 2>/dev/null)" || return 0
  [ -e "$p" ] || [ -L "$p" ] || return 0
  case "$p" in "$HOME"/*|"$PREFIX"/*|"$APPDIR"/*) ;; *) return 0 ;; esac
  # What sits in $PREFIX/bin is a command it runs: the package that provides it stays (and the browser won't delete a
  # kept command); a link out of bin, to a relay's own script say, keeps where it leads. The 1.2.10 helper kept all of
  # usr/bin, because a service's run script named the folder, and with it every package with a command.
  case "$p" in
    "$PREFIX"/bin/*/*) ;;
    "$PREFIX"/bin/*)
      keep_cmd "${p##*/}" "$why"
      if [ -L "$p" ]; then
        t="$(readlink -f -- "$p" 2>/dev/null)"
        case "$t" in "$PREFIX"/bin/*) keep_cmd "${t##*/}" "$why" ;; "$HOME"/*|"$PREFIX"/*) keep_add "$t" "$why" "$how" ;; esac
      fi
      return 0
      ;;
  esac
  if [ -L "$p" ]; then
    t="$(readlink -f -- "$p" 2>/dev/null)"
    [ -n "$t" ] && [ "$t" != "$p" ] && case "$t" in "$HOME"/*|"$PREFIX"/*) keep_add "$t" "$why" "$how" ;; esac
  fi
  if [ "$how" = exact ]; then u="$p"; else u="$(keep_unit "$p")"; fi
  [ -n "$u" ] && ! keep_structural "$u" || return 0
  [ -n "${KEPT[$u]:-}" ] || KEPT["$u"]="$why"
}

# Folders that hold everyone's things, never kept as a whole: Termux's own top folders, $PREFIX's first level and
# var's (bin, lib, var/service, var/log...), where packages and tools keep everything, and the home's shared ones.
keep_structural() {
  local rel
  case "$1" in
    "$HOME"|"$PREFIX"|"$FILES"|"$APPDIR"|"$HOME/storage"|"$HOME/storage"/*) return 0 ;;
    "$HOME"/.config|"$HOME"/.local|"$HOME"/.local/share|"$HOME"/.local/lib|"$HOME"/.local/state|"$HOME"/.cache|"$HOME"/.termux) return 0 ;;
    "$PREFIX"/*)
      rel="${1#"$PREFIX"/}"
      case "$rel" in
        lib/python*/site-packages) return 0 ;;
        */*/*) return 1 ;;
        var/*|lib/node_modules|lib/python*|share/doc|share/man) return 0 ;;
        */*) return 1 ;;
        *) return 0 ;;
      esac
      ;;
  esac
  return 1
}

# The paths a script names (~/x, $HOME/x, ${HOME}/x, $PREFIX/x, ${PREFIX}/x, or the Termux folders spelled out), one
# per line, spelled out.
paths_in() {
  local t
  while IFS= read -r t; do
    # shellcheck disable=SC2088 # a literal "~/" as the script wrote it
    case "$t" in
      "~/"*) t="$HOME/${t#"~/"}" ;;
      '$HOME/'*) t="$HOME/${t#'$HOME/'}" ;;
      '${HOME}/'*) t="$HOME/${t#'${HOME}/'}" ;;
      '$PREFIX/'*) t="$PREFIX/${t#'$PREFIX/'}" ;;
      '${PREFIX}/'*) t="$PREFIX/${t#'${PREFIX}/'}" ;;
    esac
    printf '%s\n' "$t"
  done < <(grep -aoE '(~|\$HOME|\$\{HOME\}|\$PREFIX|\$\{PREFIX\}|/data/data/com\.termux/files|/data/user/[0-9]+/com\.termux/files)/[A-Za-z0-9._+@%,~/-]+' -- "$1" 2>/dev/null)
}

# The commands of $PREFIX/bin a script runs: its #! line and every word outside comments that names one.
commands_in() {
  local w
  while IFS= read -r w; do
    [ -n "$w" ] && [ -x "$PREFIX/bin/$w" ] && printf '%s\n' "$w"
  done < <({ head -n 1 -- "$1" | grep -a '^#!'; grep -avE '^[[:space:]]*#' -- "$1"; } 2>/dev/null | tr -c 'A-Za-z0-9._+-' '\n' | sort -u)
}

# Everything one start-up script names stays, with the commands it runs. With "named", only the paths named after
# Layla's relays: a shell's own start-up files mostly set things up (PATH, sourced tool settings), which says nothing.
keep_from_script() {
  local f="$1" why="$2" only="${3:-}" p w shown
  [ -f "$f" ] || return 0
  [ "$(stat -L -c %s -- "$f" 2>/dev/null || echo 0)" -le 1048576 ] || return 0
  shown="${f/#"$HOME"/\~}"
  [ -z "$only" ] && keep_add "$f" "$why" exact
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    if [ -n "$only" ]; then
      [[ "${p,,}" =~ $KEEP_NAMES ]] || continue
    fi
    keep_add "$p" "$why ($shown)"
  done < <(paths_in "$f")
  while IFS= read -r w; do
    [ -n "$w" ] || continue
    [ -n "${KEPT_CMDS[$w]:-}" ] || KEPT_CMDS["$w"]="$why"
    # A command that leads out of $PREFIX/bin (a link to a relay's own script) keeps where it leads.
    [ -L "$PREFIX/bin/$w" ] && keep_add "$PREFIX/bin/$w" "$why ($shown)"
  done < <(commands_in "$f")
}

keep_scripts() {
  local why f d
  while IFS=$'\t' read -r why f; do
    [ -n "$f" ] && ! has_controls "$f" || continue
    keep_from_script "$f" "$why"
  done < <(
    find "$HOME/.termux/boot" -maxdepth 2 \( -type f -o -type l \) -printf 'started by Termux:Boot\t%p\n' 2>/dev/null
    find "$HOME/.shortcuts" -maxdepth 3 \( -type f -o -type l \) -printf 'a Termux:Widget shortcut\t%p\n' 2>/dev/null
    find "$HOME/.termux/tasker" -maxdepth 2 \( -type f -o -type l \) -printf 'a Termux:Tasker task\t%p\n' 2>/dev/null
    find "$PREFIX/var/spool/cron" -maxdepth 3 -type f -printf 'a cron job\t%p\n' 2>/dev/null
  )
  # termux-services: a service without a "down" file starts with Termux.
  for d in "$PREFIX/var/service"/*; do
    [ -d "$d" ] && [ -f "$d/run" ] && [ ! -e "$d/down" ] || continue
    keep_add "$d" "a termux-services service" exact
    keep_from_script "$d/run" "a termux-services service"
  done
  for f in "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile" "$HOME/.zshrc" "$HOME/.zprofile" "$HOME/.config/fish/config.fish"; do
    keep_from_script "$f" "started with every Termux session" named
  done
}

# Processes that run right now (other than this helper and what it started, and plain shells).
keep_running() {
  local d pid s ppid exe cwd arg first up n name
  local -a argv
  declare -A parent=() ours=()
  # Read with builtins only: a phone runs a few hundred processes, and a fork for each adds up.
  for d in /proc/[0-9]*; do
    pid="${d#/proc/}"
    { read -r s < "$d/stat"; } 2>/dev/null || continue
    s="${s##*) }"
    read -r _ ppid _ <<< "$s"
    [[ "$ppid" =~ ^[0-9]+$ ]] && parent["$pid"]="$ppid"
    argv=()
    { mapfile -d '' -t argv < "$d/cmdline"; } 2>/dev/null
    [[ "${argv[*]:0:3}" == *"$MARKER"* ]] && ours["$pid"]=1
  done
  ours["$$"]=1
  [ -n "${BASHPID:-}" ] && ours["$BASHPID"]=1
  for pid in "${!parent[@]}"; do
    # Ours when this helper, or anything it started, is on the way up.
    up="$pid"; n=0
    while [ -n "$up" ] && [ "$up" != 0 ] && [ "$up" != 1 ] && [ "$n" -lt 64 ]; do
      [ -n "${ours[$up]:-}" ] && continue 2
      up="${parent[$up]:-}"; n=$((n + 1))
    done
    d="/proc/$pid"
    exe="$(readlink -- "$d/exe" 2>/dev/null)" || continue
    exe="${exe% (deleted)}"
    name="${exe##*/}"
    [ -n "$name" ] || continue
    cwd="$(readlink -- "$d/cwd" 2>/dev/null)"
    # A plain shell says nothing by where it sits: only the scripts it runs count.
    case "$name" in
      bash|zsh|fish|dash|sh|mksh|login|tmux|screen|su|sudo) ;;
      *)
        [ -n "${KEPT_CMDS[$name]:-}" ] || KEPT_CMDS["$name"]="running now"
        case "$exe" in "$PREFIX"/bin/*) ;; *) keep_add "$exe" "running now ($name)" ;; esac
        [ -n "$cwd" ] && [ "$cwd" != "$HOME" ] && keep_add "$cwd" "running now ($name)"
        ;;
    esac
    argv=()
    { mapfile -d '' -t argv < "$d/cmdline"; } 2>/dev/null
    first=1
    for arg in "${argv[@]}"; do
      [ -n "$first" ] && { first=""; continue; }
      [ -n "$arg" ] && ! has_controls "$arg" || continue
      case "$arg" in -*) continue ;; /*) ;; *) [ -n "$cwd" ] && arg="$cwd/$arg" ;; esac
      case "$arg" in "$HOME"/*|"$PREFIX"/*) [ -e "$arg" ] && keep_add "$arg" "running now ($name)" ;; esac
    done
  done
}

keep_cmd() {
  [ -n "${1:-}" ] && [ -x "$PREFIX/bin/$1" ] && [ -z "${KEPT_CMDS[$1]:-}" ] && KEPT_CMDS["$1"]="$2"
  return 0
}

# What a kept relay or script runs with, when nothing runs it right now: Node.js for JavaScript, Python for Python.
# Layla starts its relays through RUN_COMMAND, so neither shell history nor dpkg knows they need these.
keep_runtimes() {
  local k ext
  for k in "${!KEPT[@]}"; do
    case "$k" in "$HOME"/*|"$PREFIX"/opt/*|"$PREFIX"/share/*|"$PREFIX"/lib/node_modules/*) ;; *) continue ;; esac
    while IFS= read -r ext; do
      case "$ext" in
        js|mjs|cjs|ts) keep_cmd node "${KEPT[$k]}" ;;
        py) keep_cmd python3 "${KEPT[$k]}"; keep_cmd python "${KEPT[$k]}" ;;
      esac
    done < <(find "$k" -maxdepth 3 \( -name node_modules -o -name .git -o -name __pycache__ \) -prune -o -type f \
        \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' -o -name '*.ts' -o -name '*.py' \) -printf '%f\n' 2>/dev/null | sed 's/.*\.//' | sort -u)
  done
}

keep_named() {
  local p
  while IFS= read -r -d '' p; do
    has_controls "$p" && continue
    relay_like "$p" || continue
    decompiled_root "$p" && continue
    keep_add "$p" "$NAMED" exact
  done < <(
    find "$HOME" -xdev -mindepth 1 -maxdepth 4 \( -path "$HOME/storage" -o -name .git -o -name node_modules \
      -o -iname '*-rootfs' -o -name installed-rootfs -o -name .gradle -o -name .m2 -o -name .rustup -o -name .npm \) -prune \
      -o -regextype posix-extended -iregex ".*/[^/]*(${KEEP_NAMES})[^/]*" -print0 -prune 2>/dev/null
    find "$PREFIX" -xdev -mindepth 1 -maxdepth 4 \( -path "$PREFIX/var/lib/proot-distro" -o -path "$PREFIX/share/doc" \
      -o -path "$PREFIX/share/man" -o -path "$PREFIX/include" -o -path "$PREFIX/glibc" -o -path "$PREFIX/tmp" \) -prune \
      -o -regextype posix-extended -iregex ".*/[^/]*(${KEEP_NAMES})[^/]*" -print0 -prune 2>/dev/null
  )
}

# Whether something named after Layla's relays can be one: a folder or a program, config or data file. Not a copy
# kept before an edit (relay.py.bak-...), a log, a dated snapshot (Layla-Debug-20260928-114347), an archive, an
# installer or a text dump: those are about the relay, not it, and the clean-up may offer them.
relay_like() {
  local n="${1##*/}"
  n="${n,,}"
  [[ "$n" =~ $SNAPSHOT_DATE ]] && return 1
  [[ "$n" =~ $COPY_MARK ]] && return 1
  case "$n" in
    *.log|*.log.[0-9]*|*.txt|*.md|*.csv|*.html|*.zip|*.7z|*.gz|*.xz|*.tgz|*.tar|*.apk|*.apks|*.xapk|*.apkm|*.bundle) return 1 ;;
  esac
  [ -d "$1" ] && return 0
  case "$n" in *.py|*.js|*.mjs|*.cjs|*.ts|*.sh|*.json|*.toml|*.yaml|*.yml|*.ini|*.conf|*.env|*.sock) return 0 ;; esac
  [ -x "$1" ] || [ -S "$1" ]
}

keep_listed() {
  local line
  [ -f "$KEEP_FILE" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    # shellcheck disable=SC2088 # a literal "~/" as the list says it
    case "$line" in ''|'#'*) continue ;; "~/"*) line="$HOME/${line#"~/"}" ;; esac
    keep_add "$line" "you chose to keep it" exact
  done < "$KEEP_FILE"
}

# ~/node_modules serves every script of the home that doesn't find a package closer: kept when a kept script is one.
home_modules_needed() {
  local k
  [ -d "$HOME/node_modules" ] || return 1
  for k in "${!KEPT[@]}"; do
    case "$k" in "$HOME"/node_modules|"$HOME"/node_modules/*|"$HOME"/.*) continue ;; "$HOME"/*) ;; *) continue ;; esac
    if [ -f "$k" ]; then
      case "$k" in *.js|*.mjs|*.cjs|*.ts) printf '%s' "$k"; return 0 ;; esac
    elif find "$k" -maxdepth 3 -name node_modules -prune -o -type f \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' -o -name '*.ts' \) \
      -print -quit 2>/dev/null | grep -q .; then
      printf '%s' "$k"
      return 0
    fi
  done
  return 1
}

load_keep() {
  local k
  [ -n "$KEEP_LOADED" ] && return 0
  KEEP_LOADED=1
  keep_listed
  keep_named
  keep_scripts
  keep_running
  keep_runtimes
  if [ -z "${KEPT[$HOME/node_modules]:-}" ] && k="$(home_modules_needed)"; then
    KEPT["$HOME/node_modules"]="${k/#"$HOME"/\~} runs with it (${KEPT[$k]})"
  fi
  return 0
}

# Why PATH has to stay, when it is kept, inside something kept or holds something kept; fails when it may go.
kept_why() {
  local p="$1" k n
  for k in "${!KEPT[@]}"; do
    case "$p" in "$k"|"$k"/*) printf '%s: %s' "${k/#"$HOME"/\~}" "${KEPT[$k]}"; return 0 ;; esac
    case "$k" in "$p"/*) printf '%s: %s' "${k/#"$HOME"/\~}" "${KEPT[$k]}"; return 0 ;; esac
  done
  case "$p" in
    "$PREFIX"/bin/*/*) ;;
    "$PREFIX"/bin/*)
      n="${p##*/}"
      if [ -n "${KEPT_CMDS[$n]:-}" ]; then printf '%s: %s' "$n" "${KEPT_CMDS[$n]}"; return 0; fi
      ;;
  esac
  return 1
}

# Packages the kept things run with: those that put a kept command in $PREFIX/bin or installed a kept path, to why.
declare -A KEPT_PKGS=()
load_kept_pkgs() {
  local info="$PREFIX/var/lib/dpkg/info" tmp k c pkg why
  load_keep
  [ -d "$info" ] || return 0
  tmp="$(mktemp "${TMPDIR:-$PREFIX/tmp}/steward-kept.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)" || return 0
  {
    for c in "${!KEPT_CMDS[@]}"; do printf '%s\t%s\n' "$PREFIX/bin/$c" "${KEPT_CMDS[$c]} ($c)"; done
    # Not what is kept only for its name: a package's own files named "relay" say nothing about Layla.
    for k in "${!KEPT[@]}"; do
      [ "${KEPT[$k]}" = "$NAMED" ] && continue
      case "$k" in "$PREFIX"/*) printf '%s\t%s\n' "$k" "${KEPT[$k]}" ;; esac
    done
  } > "$tmp"
  while IFS=$'\t' read -r pkg why; do
    [ -n "$pkg" ] && [ -z "${KEPT_PKGS[$pkg]:-}" ] && KEPT_PKGS["$pkg"]="$why"
  done < <(awk -F'\t' 'FNR == NR { want[$1] = $2; next }
    FNR == 1 { pkg = FILENAME; sub(/.*\//, "", pkg); sub(/\.list$/, "", pkg); sub(/:.*/, "", pkg) }
    ($0 in want) && !(pkg in done) { done[pkg] = 1; print pkg "\t" want[$0] }' "$tmp" "$info"/*.list 2>/dev/null)
  rm -f -- "$tmp"
  return 0
}

# keep|unkeep PATH...: adds to or takes from ~/.config/galaxy-steward/keep.
keep_edit() {
  local op="$1" p tmp
  shift
  emit V 1 "$HOME" "$PREFIX" 0
  [ "$#" -gt 0 ] || refuse "no paths given"
  mkdir -p -- "${KEEP_FILE%/*}" 2>/dev/null
  if [ ! -f "$KEEP_FILE" ]; then
    printf '%s\n' "# Galaxy Steward never cleans, deletes or moves what is listed here: one path per line, ~ for the home." > "$KEEP_FILE" 2>/dev/null
  fi
  for p in "$@"; do
    if has_controls "$p"; then result keep SKIP_UNSAFE 0 0 "" "control characters"; continue; fi
    case "$p" in "$FILES"/*) ;; *) result keep SKIP_UNSAFE 0 0 "$p" "outside Termux"; continue ;; esac
    if [ "$op" = keep ]; then
      grep -qxF -- "$p" "$KEEP_FILE" 2>/dev/null || printf '%s\n' "$p" >> "$KEEP_FILE"
      result keep KEPT 0 0 "$p" ""
    else
      tmp="$KEEP_FILE.steward-tmp"
      grep -vxF -- "$p" "$KEEP_FILE" > "$tmp" 2>/dev/null
      mv -f -- "$tmp" "$KEEP_FILE"
      result keep UNKEPT 0 0 "$p" ""
    fi
  done
  finish ok
}

# ---------------------------------------------------------------- audit

# Sizes (KiB) of everything of 1 MiB or more below the Termux files folder, from one walk. Earlier versions walked
# the whole tree four or five times (du per level, per distro, then find for large files): about six minutes on a
# real phone with proot distributions installed.
# With each, the time of the newest change anywhere below it (du --time), for the browser's "changed" dates.
declare -A BIG=() BIGT=()
load_sizes() {
  local k t p
  while IFS=$'\t' read -r k t p; do
    [ -n "$p" ] && [[ "$k" =~ ^[0-9]+$ ]] || continue
    has_controls "$p" && continue
    BIG["$p"]="$k"
    BIGT["$p"]="$t"
  done < <(du -x -a -k -t 1M --time --time-style=+%s -- "$FILES" 2>/dev/null)
}

# Bytes used by a folder: from the one-walk map, or measured directly when it is smaller than the threshold.
size_of() {
  local k="${BIG[$1]:-}"
  [ -n "$k" ] || k="$(du -x -sk -- "$1" 2>/dev/null | awk '{print $1+0; exit}')"
  echo "$(( ${k:-0} * 1024 ))"
}

audit() {
  local id info p root mode m bytes files d name active=0 r s k count=0 repo art rest
  proot_running && active=1
  emit V 1 "$HOME" "$PREFIX" "$active"
  timed keep load_keep
  timed sizes load_sizes
  # A, bytes, path, why: what stays whatever you pick (the app leaves it out of every clean-up).
  for k in "${!KEPT[@]}"; do emit A "$(size_of "$k")" "$k" "${KEPT[$k]}"; done

  # The optional extras only go into the --out file. They read the size map and nothing else of this function, so
  # they run alongside the rest (a phone has eight cores and the storage answers in parallel); each in its own
  # subshell, so one that fails can't take the report with it.
  if [ -n "$OUT" ]; then
    optional duplicates dup_large_files &
    optional sketches sketch_dirs &
    optional owners emit_owners &
    optional foreign foreign_binaries &
  fi
  local started
  started="$(now_ms)"
  LAP="$started"

  # Where the space goes (report only): the whole folder, then the largest folders one level into home and usr.
  emit U "$(size_of "$FILES")" "$FILES"
  [ -d "$APPDIR/cache" ] && emit U "$(size_of "$APPDIR/cache")" "$APPDIR/cache"
  for p in "${!BIG[@]}"; do
    case "$p" in
      "$HOME"|"$PREFIX") ;;
      "$HOME"/*|"$PREFIX"/*)
        rest="${p#"$HOME"/}"; [ "$rest" = "$p" ] && rest="${p#"$PREFIX"/}"
        case "$rest" in */*) continue ;; esac
        ;;
      *) continue ;;
    esac
    [ -d "$p" ] && [ ! -L "$p" ] && printf '%s\t%s\n' "${BIG[$p]}" "$p"
  done | sort -t "$(printf '\t')" -k1,1nr | head -n 30 | while IFS=$'\t' read -r k p; do
    emit U "$(( ${k:-0} * 1024 ))" "$p"
  done

  # Four at a time (44 s one after another on the 10-01 phone: the pycache and Gradle checks walk the whole home), in a
  # subshell whose jobs are only these, not the extras started above.
  (
    for id in $FIXED_IDS; do
      info="$(target_info "$id")" || continue
      IFS='|' read -r p root mode <<< "$info"
      [ -e "$p" ] || [ -L "$p" ] || continue
      if ! dir_beneath "$p" "$root"; then emit X "$id" "$p" "symlinked or outside the Termux folders"; continue; fi
      while [ "$(jobs -rp | wc -l)" -ge 4 ]; do wait -n 2>/dev/null || break; done
      fixed_one "$id" "$mode" "$p" &
    done
    wait
  )
  lap targets.fixed

  # Other caches in ~/.cache (review only).
  if dir_beneath "$HOME/.cache" "$HOME"; then
    for d in "$HOME/.cache"/* "$HOME/.cache"/.[!.]*; do
      [ -d "$d" ] && [ ! -L "$d" ] || continue
      has_controls "$d" && continue
      name="${d##*/}"
      case " $KNOWN_DOT_CACHE " in *" $name "*) continue ;; esac
      m="$(measure contents "$d")"
      bytes="${m%%$'\t'*}"; files="${m#*$'\t'}"
      [ "${files:-0}" -gt 0 ] && emit T other-cache "$bytes" "$files" "$d"
    done
  fi

  # Caches of Chromium and Electron apps in ~/.config, and what crashed programs left in the home.
  while IFS=$'\t' read -r -d '' id d; do
    if [ "$id" = app-logs ]; then m="$(measure old "$d")"; else m="$(measure contents "$d")"; fi
    [ "${m#*$'\t'}" -gt 0 ] && emit T "$id" "${m%%$'\t'*}" "${m#*$'\t'}" "$d"
  done < <(app_caches)
  while IFS=$'\t' read -r -d '' id d; do
    emit T "$id" "$(stat -c %s -- "$d" 2>/dev/null || echo 0)" 1 "$d"
  done < <(crash_dumps)
  # Dated reports and snapshots, and copies made before an edit.
  while IFS= read -r -d '' d; do
    m="$(measure contents "$d")"
    if [ -d "$d" ]; then emit T old-snapshot "$(size_of "$d")" "${m#*$'\t'}" "$d"
    else emit T old-snapshot "$(stat -c %s -- "$d" 2>/dev/null || echo 0)" 1 "$d"; fi
  done < <(home_snapshots)
  while IFS= read -r -d '' d; do
    emit T file-copy "$(stat -c %s -- "$d" 2>/dev/null || echo 0)" 1 "$d"
  done < <(file_copies)
  lap targets.home

  # proot distributions: inventory, and caches of inactive ones.
  while IFS= read -r -d '' r; do
    rootfs_allowed "$r" || continue
    emit R "$(size_of "$r")" "$active" "$r"
    [ "$active" = 1 ] && continue
    for s in $PROOT_PKG_CACHES; do
      dir_beneath "$r/$s" "$r" || continue
      m="$(measure contents "$r/$s")"
      [ "${m#*$'\t'}" -gt 0 ] && emit T proot-cache "${m%%$'\t'*}" "${m#*$'\t'}" "$r/$s"
    done
    while IFS= read -r -d '' d; do
      if ! proot_cache_suffix_ok "${d#"$r"/}" || ! dir_beneath "$d" "$r"; then continue; fi
      m="$(measure contents "$d")"
      [ "${m#*$'\t'}" -gt 0 ] && emit T proot-cache "${m%%$'\t'*}" "${m#*$'\t'}" "$d"
    done < <(find "$r/root" "$r/home" -xdev -mindepth 1 -maxdepth 5 -type d \( -path '*/.cache/pip' -o -path '*/.cache/uv' \
      -o -path '*/.cache/yarn' -o -path '*/.cache/go-build' -o -path '*/.npm/_cacache' -o -path '*/.npm/_npx' \
      -o -path '*/.cargo/registry/cache' -o -path '*/.cargo/registry/src' -o -path '*/.cargo/registry/index' \
      -o -path '*/.cargo/git/db' -o -path '*/.cargo/git/checkouts' \) -print0 -prune 2>/dev/null)
    for s in tmp var/tmp; do
      dir_beneath "$r/$s" "$r" || continue
      m="$(measure old "$r/$s")"
      [ "${m#*$'\t'}" -gt 0 ] && emit T proot-tmp "${m%%$'\t'*}" "${m#*$'\t'}" "$r/$s"
    done
  done < <(list_rootfs | sort -zu)
  lap targets.proot

  # Rebuildable build outputs inside Git projects in the Termux home.
  if command -v git >/dev/null 2>&1; then
    while IFS= read -r -d '' repo; do
      repo="${repo%/.git}"
      repo_allowed "$repo" || continue
      while IFS= read -r -d '' d; do
        build_ok "$repo" "$d" || continue
        m="$(measure contents "$d")"
        [ "${m#*$'\t'}" -gt 0 ] || continue
        art=0
        find "$d" -xdev -type f \( -name '*.apk' -o -name '*.aab' \) -print -quit 2>/dev/null | grep -q . && art=1
        emit B "${m%%$'\t'*}" "${m#*$'\t'}" "$art" "$repo" "$d"
        count=$((count + 1))
        [ "$count" -ge 150 ] && break 2
      done < <(find "$repo" -xdev -mindepth 1 -maxdepth 5 -name .git -prune -o -type d \( -name build -o -name node_modules \
        -o -name target -o -name .gradle -o -name .cxx -o -name .externalNativeBuild -o -name .pytest_cache \
        -o -name .mypy_cache -o -name .ruff_cache -o -name coverage -o -name .next -o -name .turbo -o -name .venv -o -name venv \) \
        -print0 -prune 2>/dev/null)
    done < <(find "$HOME" -xdev -maxdepth 4 \( -path "$HOME/storage" -o -path "$HOME/.*" \) -prune -o -name .git -print0 -prune 2>/dev/null)
  else
    emit W "git is not installed, so project build outputs were not checked"
  fi
  lap targets.build

  # Leftovers that aren't caches: decompiled apps, APKs, x86-64 NDKs, and proot's orphaned hard-link copies.
  while IFS= read -r -d '' d; do
    m="$(measure contents "$d")"
    emit T decompiled "$(size_of "$d")" "${m#*$'\t'}" "$d"
  done < <(decompiled_dirs)
  while IFS= read -r -d '' p; do
    emit T home-apk "$(stat -c %s -- "$p" 2>/dev/null || echo 0)" 1 "$p"
  done < <(home_apks)
  while IFS= read -r -d '' d; do
    emit T foreign-ndk "$(size_of "$d")" "$(find "$d" -xdev -type f 2>/dev/null | wc -l)" "$d"
  done < <(foreign_ndks "$HOME")
  while IFS= read -r -d '' r; do
    rootfs_allowed "$r" || continue
    while IFS= read -r -d '' d; do
      [[ "${d#"$r"/}" =~ $DISTRO_FREE ]] || continue
      emit T foreign-ndk "$(size_of "$d")" "$(find "$d" -xdev -type f 2>/dev/null | wc -l)" "$d"
    done < <(foreign_ndks "$r")
    while IFS= read -r -d '' p; do
      emit T l2s-orphan "$(stat -c %s -- "$p" 2>/dev/null || echo 0)" 1 "$p"
    done < <(l2s_orphans "$r")
  done < <(list_rootfs | sort -zu)

  lap targets.leftovers
  emit Y targets "$(( $(now_ms) - started ))"

  # The size map itself, for browsing Termux folder by folder in the app: only into the --out file, because the
  # result bundle that carries stdout is too small for it.
  if [ -n "$OUT" ]; then
    for p in "${!BIG[@]}"; do
      [ -L "$p" ] && continue
      if [ -d "$p" ]; then emit S "$(( ${BIG[$p]} * 1024 ))" d "$p" "${BIGT[$p]:-0}"
      elif [ -f "$p" ]; then emit S "$(( ${BIG[$p]} * 1024 ))" f "$p" "${BIGT[$p]:-0}"; fi
    done
  fi

  # Largest files anywhere in Termux (report only), from the same walk.
  for p in "${!BIG[@]}"; do
    [ -f "$p" ] && [ ! -L "$p" ] && printf '%s\t%s\n' "${BIG[$p]}" "$p"
  done | sort -t "$(printf '\t')" -k1,1nr | head -n 30 | while IFS=$'\t' read -r k p; do
    emit L "$(stat -c %s -- "$p" 2>/dev/null || echo $(( k * 1024 )))" "$(stat -c %Y -- "$p" 2>/dev/null || echo 0)" "$p"
  done

  # The extras started above.
  wait
  finish ok
}

# T for one fixed target, measured the way its clean would remove it.
fixed_one() {
  local m
  m="$(measure "$2" "$3")"
  [ "${m#*$'\t'}" -gt 0 ] && emit T "$1" "${m%%$'\t'*}" "${m#*$'\t'}" "$3"
  return 0
}

# ---------------------------------------------------------------- clean

result() {
  # id status before after path note
  emit D "$@"
}

clear_path() {
  local mode="$1" p="$2" root="$3" f d
  case "$mode" in
    debs)
      while IFS= read -r -d '' f; do
        dir_beneath "$p" "$root" && file_beneath "$f" "$p" && rm -f -- "$f"
      done < <(find "$p" -xdev -mindepth 1 -maxdepth 1 -type f -name '*.deb' -print0 2>/dev/null)
      ;;
    aptbin)
      for f in "$p/pkgcache.bin" "$p/srcpkgcache.bin"; do
        file_beneath "$f" "$root" && rm -f -- "$f"
      done
      ;;
    old)
      while IFS= read -r -d '' f; do
        dir_beneath "$p" "$root" && file_beneath "$f" "$p" && rm -f -- "$f"
      done < <(find "$p" -xdev -type f -mtime +7 -print0 2>/dev/null)
      while IFS= read -r -d '' d; do
        dir_beneath "$d" "$p" && rmdir -- "$d" 2>/dev/null
      done < <(find "$p" -xdev -mindepth 1 -depth -type d -empty -mtime +7 -print0 2>/dev/null)
      ;;
    oldlog)
      while IFS= read -r -d '' f; do
        dir_beneath "$p" "$root" && file_beneath "$f" "$p" && rm -f -- "$f"
      done < <(find "$p" -xdev -type f -name '*.log' -mtime +7 -print0 2>/dev/null)
      ;;
    pycache)
      while IFS= read -r -d '' d; do
        [ "${d##*/}" = __pycache__ ] && dir_beneath "$d" "$p" && rm -rf -- "$d"
      done < <(pycache_dirs "$p")
      ;;
    oldversions|oldversions-unsure)
      dir_beneath "$p" "$root" || return 1
      while IFS= read -r -d '' e; do
        beneath "$e" "$p" && rm -rf -- "$e"
      done < <(if [ "$mode" = oldversions ]; then old_versions "$p"; else old_versions "$p" unsure; fi)
      ;;
    oldgradle)
      dir_beneath "$p" "$root" || return 1
      while IFS= read -r -d '' e; do
        beneath "$e" "$p" && rm -rf -- "$e"
      done < <(old_gradle_dists "$p")
      ;;
    gomod)
      # Everything but the download cache, which has its own target; Go makes it read-only.
      dir_beneath "$p" "$root" || return 1
      while IFS= read -r -d '' e; do
        beneath "$e" "$p" && chmod -R u+w -- "$e" 2>/dev/null && rm -rf -- "$e"
      done < <(find "$p" -xdev -mindepth 1 -maxdepth 1 ! -name cache -print0 2>/dev/null)
      ;;
    contents|contents-rw)
      dir_beneath "$p" "$root" || return 1
      # Go makes its module cache read-only; make it writable again before removing it.
      [ "$mode" = contents-rw ] && chmod -R u+w -- "$p" 2>/dev/null
      dir_beneath "$p" "$root" && find "$p" -xdev -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + 2>/dev/null
      ;;
  esac
  return 0
}

clean_one() {
  local spec="$1" id p info root mode before after repo r suffix why
  id="${spec%%=*}"
  p=""
  [ "$id" != "$spec" ] && p="${spec#*=}"
  if has_controls "$spec"; then result "$id" SKIP_UNSAFE 0 0 "" "control characters"; return; fi
  # What only goes by age, or is rebuilt on the next run, can't break what is kept; anything else that would touch a
  # kept path stays.
  case "$id" in
    pycache|proot-tmp|termux-tmp|var-tmp|termux-var-log|npm-logs|gradle-daemon-logs|apt-archives|apt-pkgcache|claude-versions|claude-versions-unsure|app-logs) ;;
    *)
      # Fixed targets name their folder in target_info; the others carry it.
      [ -n "$p" ] || { info="$(target_info "$id")" && p="${info%%|*}"; }
      if [ -n "$p" ] && why="$(kept_why "$p")"; then result "$id" SKIP_KEPT 0 0 "$p" "$why"; return; fi
      ;;
  esac

  case "$id" in
    other-cache)
      root="$HOME"; mode=contents
      if [ "$(dirname -- "$p")" != "$HOME/.cache" ] || ! dir_beneath "$p" "$HOME"; then
        result "$id" SKIP_UNSAFE 0 0 "$p" "not a folder directly in ~/.cache"
        return
      fi
      case " $KNOWN_DOT_CACHE " in *" ${p##*/} "*) result "$id" SKIP_UNSAFE 0 0 "$p" "covered by its own target"; return ;; esac
      ;;
    proot-cache|proot-tmp)
      if proot_running; then result "$id" SKIP_ACTIVE 0 0 "$p" "a proot distribution is running"; return; fi
      r="$(rootfs_of "$p")" || { result "$id" SKIP_UNSAFE 0 0 "$p" "not inside a known proot distribution"; return; }
      suffix="${p#"$r"/}"
      root="$r"
      if [ "$id" = proot-tmp ]; then
        mode=old
        case "$suffix" in tmp|var/tmp) ;; *) result "$id" SKIP_UNSAFE 0 0 "$p" "not a temp folder"; return ;; esac
      else
        mode=contents
        proot_cache_suffix_ok "$suffix" || { result "$id" SKIP_UNSAFE 0 0 "$p" "not a package-manager cache"; return; }
      fi
      dir_beneath "$p" "$r" || { result "$id" SKIP_UNSAFE 0 0 "$p" "symlinked or missing"; return; }
      ;;
    build)
      if ! command -v git >/dev/null 2>&1; then result "$id" SKIP_UNSAFE 0 0 "$p" "git is not installed"; return; fi
      repo="$(repo_of "$p")" || { result "$id" SKIP_UNSAFE 0 0 "$p" "not inside a Git project"; return; }
      repo_allowed "$repo" || { result "$id" SKIP_UNSAFE 0 0 "$p" "project is outside the Termux home"; return; }
      build_ok "$repo" "$p" || { result "$id" SKIP_TRACKED 0 0 "$p" "not ignored by Git, tracked, or holds credentials"; return; }
      before="$(measure contents "$p")"; before="${before%%$'\t'*}"
      rm -rf -- "$p" 2>/dev/null
      if [ -e "$p" ] || [ -L "$p" ]; then
        after="$(measure contents "$p")"; after="${after%%$'\t'*}"
        result "$id" PARTIAL "$before" "$after" "$p" "some files could not be removed"
      else
        result "$id" CLEARED "$before" 0 "$p" "ignored build output"
      fi
      return
      ;;
    decompiled|home-apk|foreign-ndk|l2s-orphan|prefix-unowned|old-python|crash-log|heap-dump|old-snapshot|file-copy)
      leftover_one "$id" "$p"
      return
      ;;
    app-cache|app-logs)
      app_cache_ok "$id" "$p" || { result "$id" SKIP_UNSAFE 0 0 "$p" "not a cache folder of an app's profile in ~/.config"; return; }
      root="$HOME"
      if [ "$id" = app-logs ]; then mode=old; else mode=contents; fi
      ;;
    *)
      info="$(target_info "$id")" || { result "$id" SKIP_UNKNOWN 0 0 "" "unknown target"; return; }
      IFS='|' read -r p root mode <<< "$info"
      ;;
  esac

  if [ ! -e "$p" ] && [ ! -L "$p" ]; then result "$id" NO_CHANGE 0 0 "$p" "already gone"; return; fi
  dir_beneath "$p" "$root" || { result "$id" SKIP_UNSAFE 0 0 "$p" "symlinked or outside the Termux folders"; return; }
  before="$(measure "$mode" "$p")"; before="${before%%$'\t'*}"
  clear_path "$mode" "$p" "$root"
  after="$(measure "$mode" "$p")"; after="${after%%$'\t'*}"
  if [ "${after:-0}" -lt "${before:-0}" ]; then
    result "$id" CLEARED "$before" "$after" "$p" ""
  else
    result "$id" NO_CHANGE "$before" "$after" "$p" "nothing could be removed"
  fi
}

# Removes one leftover the audit found, after checking again that it still is one.
leftover_one() {
  local id="$1" p="$2" r="" before why=""
  case "$id" in
    decompiled) decompiled_ok "$p" || why="no longer a decompiled app on its own (in a Git project, or holds a key)" ;;
    home-apk) home_apk_ok "$p" || why="not an APK in the home" ;;
    crash-log|heap-dump) [ "$(crash_dump_kind "$p")" = "$id" ] || why="not a crash log or dump older than a day" ;;
    old-snapshot) snapshot_ok "$p" || why="no longer a dated snapshot left alone for three days (changed, a project, or holds a key)" ;;
    file-copy) file_copy_ok "$p" || why="not a copy next to the file it copies" ;;
    foreign-ndk)
      if r="$(rootfs_of "$p")"; then
        if ! [[ "${p#"$r"/}" =~ $DISTRO_FREE ]] || ! dir_beneath "$p" "$r"; then why="part of the distribution's system"; fi
      else
        r=""
        dir_beneath "$p" "$HOME" || why="outside the home and the distributions"
      fi
      if [ -z "$why" ] && ! foreign_ndk_ok "$p"; then why="not an NDK built only for x86-64"; fi
      if [ -z "$why" ] && x86_emulator "${r:-$HOME}"; then why="an x86-64 emulator is installed"; fi
      ;;
    prefix-unowned|old-python)
      if ! prefix_leftover_ok "$p" || [ ! -d "$p" ]; then
        why="not a folder directly in \$PREFIX's opt, share, lib, libexec or include"
      elif [ -n "$(pkg_owners "$p")" ]; then
        why="a package installed files in it"
      elif [ "$id" = old-python ] && [[ "${p#"$PREFIX"/}" =~ ^lib/python([0-9]+\.[0-9]+)$ ]] && [ -e "$PREFIX/bin/python${BASH_REMATCH[1]}" ]; then
        why="that Python is installed again"
      else
        why="$(private_key_in "$p")"
        [ -n "$why" ] && why="holds ${why##*/}"
      fi
      ;;
    l2s-orphan)
      if r="$(rootfs_of "$p")"; then
        case "$p" in "$r"/.l2s/*) file_beneath "$p" "$r" || why="symlinked or missing" ;; *) why="not in proot's .l2s store" ;; esac
        if [ -z "$why" ] && l2s_referenced "$r" "$p"; then why="a file in the distribution still points at it"; fi
      else
        why="not inside a known proot distribution"
      fi
      ;;
  esac
  if [ -n "$why" ]; then result "$id" SKIP_UNSAFE 0 0 "$p" "$why"; return; fi
  if [ -n "$r" ] && proot_running; then result "$id" SKIP_ACTIVE 0 0 "$p" "a proot distribution is running"; return; fi
  before="$(size_now "$p")"
  [ -d "$p" ] && chmod -R u+rwX -- "$p" 2>/dev/null
  rm -rf -- "$p" 2>/dev/null
  # Commands that led into a folder of $PREFIX now lead nowhere: they go with it.
  case "$id" in prefix-unowned|old-python) [ -e "$p" ] || drop_dead_links "$p" ;; esac
  if [ -e "$p" ]; then
    result "$id" PARTIAL "$before" "$(size_now "$p")" "$p" "some files could not be removed"
  else
    result "$id" CLEARED "$before" 0 "$p" ""
  fi
}

# Removes the links in $PREFIX/bin that pointed into DIR (now gone) and point nowhere.
drop_dead_links() {
  local gone="$1" b raw abs
  for b in "$PREFIX/bin"/*; do
    [ -L "$b" ] && [ ! -e "$b" ] || continue
    raw="$(readlink -- "$b" 2>/dev/null)" || continue
    case "$raw" in /*) abs="$raw" ;; *) abs="$(realpath -m -- "$PREFIX/bin/$raw" 2>/dev/null)" || continue ;; esac
    case "$abs" in "$gone"/*) rm -f -- "$b" ;; esac
  done
}

clean() {
  local spec
  emit V 1 "$HOME" "$PREFIX" 0
  load_keep
  for spec in "$@"; do clean_one "$spec"; done
  finish ok
}

# ---------------------------------------------------------------- packages

PKG_NAME='^[a-z0-9][a-z0-9+._-]*$'

# Termux can't work without these, and this script needs them to run at all. Never removed, whatever apt allows.
KEEP_PKGS=" apt bash coreutils dash dpkg findutils gawk grep libandroid-support ncurses readline sed tar termux-am termux-am-socket termux-core termux-exec termux-keyring termux-tools "

declare -A PKG_SIZE=() PKG_PROTECTED=()

# Fields are separated by the unit separator, not tabs: read collapses runs of tabs, which would shift every field
# after an empty one.
US=$'\037'

# Installed packages: name, bytes, protected, version, dependencies and summary, from dpkg itself.
load_packages() {
  local name size status essential priority version deps predeps summary
  while IFS="$US" read -r name size status essential priority version deps predeps summary; do
    case "$status" in *" installed") ;; *) continue ;; esac
    [[ "$name" =~ $PKG_NAME ]] || continue
    PKG_SIZE["$name"]=$(( ${size:-0} * 1024 ))
    PKG_PROTECTED["$name"]=0
    if [ "$essential" = yes ] || [ "$priority" = required ]; then PKG_PROTECTED["$name"]=1; fi
    case "$KEEP_PKGS" in *" $name "*) PKG_PROTECTED["$name"]=1 ;; esac
    printf "%s$US%s$US%s$US%s$US%s$US%s\n" "$name" "${PKG_SIZE[$name]}" "${PKG_PROTECTED[$name]}" "$version" "$deps${predeps:+, $predeps}" "$summary"
  done < <("$PREFIX/bin/dpkg-query" -W -f="\${Package}$US\${Installed-Size}$US\${Status}$US\${Essential}$US\${Priority}$US\${Version}$US\${Depends}$US\${Pre-Depends}$US\${binary:Summary}\n" 2>/dev/null | tr -d '\r')
}

# When you last ran each command, from your shell history: name<TAB>time<TAB>times run. bash writes times only with
# HISTTIMEFORMAT set (a "#1695500000" line before each command), zsh with extended history (": 1695500000:0;cmd"),
# fish always ("when: 1695500000"). A command from a history without times gets time 0: used, but not known when.
HISTORY_AWK='
function seen(line, ts,    n, i, m, j, w, seg, words) {
  gsub(/&&|\|\||[|;&()`]/, "\n", line)
  n = split(line, seg, "\n")
  for (i = 1; i <= n; i++) {
    m = split(seg[i], words, /[ \t]+/)
    for (j = 1; j <= m; j++) {
      w = words[j]
      if (w == "" || w ~ /^[A-Za-z_][A-Za-z0-9_]*=/) continue
      if (w == "sudo" || w == "env" || w == "time" || w == "nohup" || w == "exec" || w == "command" || w == "builtin" || w == "nice" || w == "then" || w == "do" || w == "!") continue
      sub(/^.*\//, "", w)
      if (w ~ /^[A-Za-z0-9][A-Za-z0-9._+-]*$/) { if (ts > last[w]) last[w] = ts; count[w]++ }
      total++
      if (ts > 0 && (oldest == 0 || ts < oldest)) oldest = ts
      break
    }
  }
}
FILENAME != file { file = FILENAME; ts = 0; pending = "" }
/^#[0-9]+$/ && kind[FILENAME] == "bash" { ts = substr($0, 2) + 0; next }
kind[FILENAME] == "zsh" && /^: [0-9]+:[0-9]+;/ { t = $0; sub(/^: /, "", t); ts = t + 0; sub(/^: [0-9]+:[0-9]+;/, ""); seen($0, ts); next }
kind[FILENAME] == "fish" && /^- cmd: / { pending = substr($0, 8); next }
kind[FILENAME] == "fish" && /^  when: [0-9]+/ { if (pending != "") seen(pending, substr($0, 9) + 0); pending = ""; next }
kind[FILENAME] == "fish" { next }
{ seen($0, ts); if (kind[FILENAME] == "bash") ts = 0 }
END { for (w in count) printf "%s\t%d\t%d\n", w, last[w], count[w]; printf "#span\t%d\t%d\n", oldest, total }'

declare -A CMD_LAST=() CMD_USES=()
load_history() {
  local c t n files=() kinds=""
  [ -f "$HOME/.bash_history" ] && { files+=("$HOME/.bash_history"); kinds+="bash "; }
  [ -f "$HOME/.zsh_history" ] && { files+=("$HOME/.zsh_history"); kinds+="zsh "; }
  [ -f "$HOME/.local/share/fish/fish_history" ] && { files+=("$HOME/.local/share/fish/fish_history"); kinds+="fish "; }
  [ "${#files[@]}" -gt 0 ] || return 0
  while IFS=$'\t' read -r c t n; do
    [ -n "$c" ] && [[ "$t$n" =~ ^[0-9]+$ ]] || continue
    CMD_LAST["$c"]="$t"
    CMD_USES["$c"]="$n"
  done < <(awk -v kinds="$kinds" -v list="${files[*]}" 'BEGIN { nk = split(kinds, k, " "); split(list, f, " "); for (i = 1; i <= nk; i++) kind[f[i]] = k[i] }'"$HISTORY_AWK" "${files[@]}" 2>/dev/null)
}

# The newest time and total uses of the comma-separated commands in $1, into LAST and USES.
usage_of() {
  local c
  LAST=0
  USES=0
  IFS=, read -r -a cmds <<< "${1:-}"
  for c in "${cmds[@]}"; do
    [ -n "$c" ] || continue
    [ "${CMD_LAST[$c]:-0}" -gt "$LAST" ] && LAST="${CMD_LAST[$c]}"
    USES=$(( USES + ${CMD_USES[$c]:-0} ))
  done
}

packages() {
  local name size protected version deps summary m cmd pkg f t
  declare -A manual=() commands=() installed=()
  emit V 1 "$HOME" "$PREFIX" 0
  [ -x "$PREFIX/bin/dpkg-query" ] || refuse "dpkg-query is missing"
  crash_point packages
  load_kept_pkgs
  while IFS= read -r name; do [ -n "$name" ] && manual["$name"]=1; done < <("$PREFIX/bin/apt-mark" showmanual 2>/dev/null)
  load_history
  # The commands each package put in $PREFIX/bin, and when it was installed or last updated (its file list's time).
  while IFS=$'\t' read -r cmd pkg; do
    [ -n "$cmd" ] && [ -n "$pkg" ] || continue
    commands["$pkg"]+="${commands[$pkg]:+,}$cmd"
  done < <(awk -v bin="$PREFIX/bin/" 'index($0, bin) == 1 { c = substr($0, length(bin) + 1); if (c != "" && c !~ /\//) { f = FILENAME; sub(/.*\//, "", f); sub(/\.list$/, "", f); sub(/:.*/, "", f); print c "\t" f } }' \
      "$PREFIX/var/lib/dpkg/info/"*.list 2>/dev/null)
  while read -r t f; do
    f="${f##*/}"; f="${f%.list}"; f="${f%%:*}"
    [ -n "$f" ] && [[ "$t" =~ ^[0-9]+$ ]] || continue
    installed["$f"]="$t"
  done < <(stat -c '%Y %n' -- "$PREFIX/var/lib/dpkg/info/"*.list 2>/dev/null)
  while IFS="$US" read -r name size protected version deps summary; do
    has_controls "$version$deps$summary" && continue
    m=0; [ -n "${manual[$name]:-}" ] && m=1
    usage_of "${commands[$name]:-}"
    emit K "$name" "$size" "$m" "$protected" "$version" "$deps" "$summary" "${installed[$name]:-0}" "$LAST" "$USES" \
      "$(printf '%s' "${commands[$name]:-}" | cut -d, -f1-12)" "${KEPT_PKGS[$name]:-}"
  done < <(load_packages)
  # Each package manager on its own: one that trips can't cost the package list.
  # J, oldest time in the history (0 when it keeps none), commands in it: how far "never run" can be trusted.
  emit J "${CMD_LAST[#span]:-0}" "${CMD_USES[#span]:-0}"
  optional npm npm_programs
  optional pip pip_programs
  optional cargo cargo_programs
  finish ok
}

# ---------------------------------------------------------------- programs other package managers installed

# M, manager, name, version, bytes, installed, last used, uses, protected, commands, path: global npm packages, pip
# packages outside dpkg's, and cargo installs.
programs() {
  npm_programs
  pip_programs
  cargo_programs
}

npm_programs() {
  local root="$PREFIX/lib/node_modules" b link name d version t p
  declare -A bins=()
  [ -d "$root" ] || return 0
  # Commands npm linked into $PREFIX/bin, by the package they belong to.
  for b in "$PREFIX/bin"/*; do
    [ -L "$b" ] || continue
    link="$(readlink -- "$b" 2>/dev/null)" || continue
    case "$link" in *node_modules/*) ;; *) continue ;; esac
    name="${link#*node_modules/}"
    case "$name" in @*/*) name="${name%%/*}/$(printf '%s' "${name#*/}" | cut -d/ -f1)" ;; *) name="${name%%/*}" ;; esac
    [ -n "$name" ] && [ "${name%/}" = "$name" ] || continue
    bins["$name"]+="${bins[$name]:+,}${b##*/}"
  done
  for d in "$root"/* "$root"/@*/*; do
    [ -d "$d" ] && [ ! -L "$d" ] && [ -f "$d/package.json" ] || continue
    name="${d#"$root"/}"
    has_controls "$name" && continue
    p=0
    case "$name" in npm|corepack) p=1 ;; esac
    version="$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$d/package.json" 2>/dev/null | head -n 1)"
    t="$(stat -c %Y -- "$d/package.json" 2>/dev/null || echo 0)"
    usage_of "${bins[$name]:-}"
    emit M npm "$name" "$version" "$(size_now "$d")" "$t" "$LAST" "$USES" "$p" "${bins[$name]:-}" "$d"
  done
}

pip_programs() {
  local site info name version t bytes cmds p
  declare -A owned=()
  while IFS= read -r info; do [ -n "$info" ] && owned["$info"]=1; done < <(grep -h '\.dist-info$' "$PREFIX/var/lib/dpkg/info/"*.list 2>/dev/null)
  for site in "$PREFIX"/lib/python3*/site-packages "$HOME"/.local/lib/python3*/site-packages; do
    [ -d "$site" ] && [ ! -L "$site" ] || continue
    for info in "$site"/*.dist-info; do
      [ -d "$info" ] && [ -f "$info/RECORD" ] || continue
      [ -n "${owned[$info]:-}" ] && continue
      name="$(sed -n 's/^Name: //p' "$info/METADATA" 2>/dev/null | head -n 1 | tr -d '\r')"
      version="$(sed -n 's/^Version: //p' "$info/METADATA" 2>/dev/null | head -n 1 | tr -d '\r')"
      [ -n "$name" ] && [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || continue
      p=0
      case "$(printf '%s' "$name" | tr '[:upper:]_' '[:lower:]-')" in pip|setuptools|wheel) p=1 ;; esac
      t="$(stat -c %Y -- "$info" 2>/dev/null || echo 0)"
      bytes="$(awk -F, '$NF ~ /^[0-9]+$/ { s += $NF } END { printf "%d", s + 0 }' "$info/RECORD" 2>/dev/null)"
      cmds="$(awk -F, '$1 ~ /^\.\.\/\.\.\/\.\.\/bin\/[^\/]+$/ { sub(/^.*\//, "", $1); printf "%s%s", (n++ ? "," : ""), $1 }' "$info/RECORD" 2>/dev/null)"
      usage_of "$cmds"
      emit M pip "$name" "$version" "${bytes:-0}" "$t" "$LAST" "$USES" "$p" "$cmds" "$info"
    done
  done
}

cargo_programs() {
  local toml="$HOME/.cargo/.crates.toml" line name version cmds b bytes t
  [ -f "$toml" ] || return 0
  # "ripgrep 14.1.0 (registry+https://github.com/rust-lang/crates.io-index)" = ["rg"]
  while IFS= read -r line; do
    [[ "$line" =~ ^\"([A-Za-z0-9_-]+)\ ([^ ]+)\ .*\"\ =\ \[(.*)\]$ ]] || continue
    name="${BASH_REMATCH[1]}"; version="${BASH_REMATCH[2]}"
    cmds="$(printf '%s' "${BASH_REMATCH[3]}" | tr -d '" ' )"
    bytes=0; t=0
    IFS=, read -r -a list <<< "$cmds"
    for b in "${list[@]}"; do
      [ -f "$HOME/.cargo/bin/$b" ] || continue
      bytes=$(( bytes + $(stat -c %s -- "$HOME/.cargo/bin/$b" 2>/dev/null || echo 0) + 0 ))
      t="$(stat -c %Y -- "$HOME/.cargo/bin/$b" 2>/dev/null || echo 0)"
    done
    usage_of "$cmds"
    emit M cargo "$name" "$version" "$bytes" "$t" "$LAST" "$USES" 0 "$cmds" "$HOME/.cargo/bin"
  done < "$toml"
}

NPM_NAME='^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$'
PY_NAME='^[A-Za-z0-9][A-Za-z0-9._-]*$'

# Uninstalls programs with the package manager that installed them, and checks each is gone.
prog_remove() {
  local mgr="${1:-}" n d before log why b w
  emit V 1 "$HOME" "$PREFIX" 0
  load_keep
  shift || true
  [ "$#" -gt 0 ] || refuse "no programs given"
  case "$mgr" in
    npm)
      for n in "$@"; do [[ "$n" =~ $NPM_NAME ]] || refuse "not an npm package name: $n"; done
      command -v npm > /dev/null 2>&1 || refuse "npm is not installed"
      for n in "$@"; do
        d="$PREFIX/lib/node_modules/$n"
        case "$n" in npm|corepack) result "$n" SKIP_PROTECTED 0 0 "$d" "npm needs it"; continue ;; esac
        dir_beneath "$d" "$PREFIX" || { result "$n" NO_CHANGE 0 0 "$d" "not installed"; continue; }
        if why="$(kept_why "$d")"; then result "$n" SKIP_KEPT 0 0 "$d" "$why"; continue; fi
        before="$(size_now "$d")"
        log="$(npm rm -g -- "$n" 2>&1 < /dev/null)" || emit W "npm: $(printf '%s\n' "$log" | tail -n 1)"
        if [ -e "$d" ]; then result "$n" FAILED "$before" "$before" "$d" "still installed"; else result "$n" REMOVED "$before" 0 "$d" ""; fi
      done
      ;;
    pip)
      for n in "$@"; do [[ "$n" =~ $PY_NAME ]] || refuse "not a Python package name: $n"; done
      for n in "$@"; do
        case "$(printf '%s' "$n" | tr '[:upper:]_' '[:lower:]-')" in pip|setuptools|wheel) result "$n" SKIP_PROTECTED 0 0 "" "pip needs it"; continue ;; esac
        d="$(pip_info_dir "$n")"
        [ -n "$d" ] || { result "$n" NO_CHANGE 0 0 "" "not installed with pip"; continue; }
        before="$(awk -F, '$NF ~ /^[0-9]+$/ { s += $NF } END { printf "%d", s + 0 }' "$d/RECORD" 2>/dev/null)"
        log="$(python3 -m pip uninstall -y -- "$n" 2>&1 < /dev/null)"
        # Termux's Python may be marked as managed by its package manager (PEP 668); you picked this pip package.
        case "$log" in *externally-managed-environment*) log="$(python3 -m pip uninstall -y --break-system-packages -- "$n" 2>&1 < /dev/null)" ;; esac
        [ -e "$d" ] && emit W "pip: $(printf '%s\n' "$log" | tail -n 1)"
        if [ -e "$d" ]; then result "$n" FAILED "${before:-0}" "${before:-0}" "$d" "still installed"; else result "$n" REMOVED "${before:-0}" 0 "$d" ""; fi
      done
      ;;
    cargo)
      for n in "$@"; do [[ "$n" =~ ^[A-Za-z0-9_-]+$ ]] || refuse "not a crate name: $n"; done
      command -v cargo > /dev/null 2>&1 || refuse "cargo is not installed"
      for n in "$@"; do
        grep -q "^\"$n " "$HOME/.cargo/.crates.toml" 2>/dev/null || { result "$n" NO_CHANGE 0 0 "" "not installed with cargo"; continue; }
        why=""
        for b in $(grep "^\"$n " "$HOME/.cargo/.crates.toml" 2>/dev/null | head -n 1 | sed 's/^[^=]*=//' | tr -d '[]" ' | tr ',' ' '); do
          [ -n "$b" ] || continue
          if w="$(kept_why "$HOME/.cargo/bin/$b")"; then why="$w"; break; fi
          [ -n "${KEPT_CMDS[$b]:-}" ] && { why="$b: ${KEPT_CMDS[$b]}"; break; }
        done
        if [ -n "$why" ]; then result "$n" SKIP_KEPT 0 0 "" "$why"; continue; fi
        before="$(cargo_bytes "$n")"
        log="$(cargo uninstall -- "$n" 2>&1 < /dev/null)" || emit W "cargo: $(printf '%s\n' "$log" | tail -n 1)"
        if grep -q "^\"$n " "$HOME/.cargo/.crates.toml" 2>/dev/null; then result "$n" FAILED "$before" "$before" "" "still installed"
        else result "$n" REMOVED "$before" 0 "" ""; fi
      done
      ;;
    *) refuse "unknown package manager: $mgr" ;;
  esac
  finish ok
}

# The dist-info folder of a pip-installed (not dpkg-installed) Python package, or nothing.
pip_info_dir() {
  local want site info name
  want="$(printf '%s' "$1" | tr '[:upper:]_' '[:lower:]-')"
  for site in "$PREFIX"/lib/python3*/site-packages "$HOME"/.local/lib/python3*/site-packages; do
    for info in "$site"/*.dist-info; do
      [ -f "$info/METADATA" ] || continue
      name="$(sed -n 's/^Name: //p' "$info/METADATA" | head -n 1 | tr -d '\r' | tr '[:upper:]_' '[:lower:]-')"
      [ "$name" = "$want" ] || continue
      grep -qxF -- "$info" "$PREFIX/var/lib/dpkg/info/"*.list 2>/dev/null && return 0
      printf '%s' "$info"
      return 0
    done
  done
}

cargo_bytes() {
  local line b bytes=0
  line="$(grep "^\"$1 " "$HOME/.cargo/.crates.toml" 2>/dev/null | head -n 1)"
  for b in $(printf '%s' "${line#*=}" | tr -d '[]" ' | tr ',' ' '); do
    [ -f "$HOME/.cargo/bin/$b" ] && bytes=$(( bytes + $(stat -c %s -- "$HOME/.cargo/bin/$b" 2>/dev/null || echo 0) ))
  done
  echo "$bytes"
}

valid_packages() {
  local n
  [ "$#" -gt 0 ] || refuse "no packages given"
  for n in "$@"; do [[ "$n" =~ $PKG_NAME ]] || refuse "not a package name: $n"; done
}

# What apt would remove (one name per line), from its own dry run; fails with apt's message.
simulate_remove() {
  local out
  out="$("$PREFIX/bin/apt-get" -s -q remove "$@" 2>&1)" || { printf '%s\n' "$out" | tail -n 1 >&2; return 1; }
  printf '%s\n' "$out" | awk '$1 == "Remv" { print $2 }' | sed 's/:.*//'
}

# For each package apt would remove: P, name, bytes, why (requested / dependent: something you picked needs it gone
# too / orphan: nothing needs it any more, only with --autoremove) and whether it is protected.
pkg_plan() {
  local n kind err
  declare -A want=() base=()
  emit V 1 "$HOME" "$PREFIX" 0
  valid_packages "$@"
  load_packages > /dev/null
  load_kept_pkgs
  for n in "$@"; do want["$n"]=1; done
  if ! err="$(simulate_remove "$@" 2>&1 >/dev/null)"; then emit W "apt refused: $err"; finish ok; fi
  while IFS= read -r n; do base["$n"]=1; done < <(simulate_remove "$@" 2>/dev/null)
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    if [ -n "${want[$n]:-}" ]; then kind=requested; elif [ -n "${base[$n]:-}" ]; then kind=dependent; else kind=orphan; fi
    emit P "$n" "${PKG_SIZE[$n]:-0}" "$kind" "${PKG_PROTECTED[$n]:-0}" "${KEPT_PKGS[$n]:-}"
  done < <(simulate_remove --autoremove "$@" 2>/dev/null)
  finish ok
}

pkg_remove() {
  local auto="" n log refused=0
  declare -A gone=()
  emit V 1 "$HOME" "$PREFIX" 0
  if [ "${1:-}" = --autoremove ]; then auto=--autoremove; shift; fi
  valid_packages "$@"
  load_packages > /dev/null
  load_kept_pkgs
  # Checked again here: the dry run decides, and one protected package stops the whole removal.
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    gone["$n"]=1
    if [ "${PKG_PROTECTED[$n]:-0}" = 1 ]; then result "$n" SKIP_PROTECTED 0 0 "" "Termux needs it"; refused=1
    elif [ -n "${KEPT_PKGS[$n]:-}" ]; then result "$n" SKIP_KEPT 0 0 "" "${KEPT_PKGS[$n]}"; refused=1; fi
  done < <(simulate_remove $auto "$@" 2>/dev/null)
  [ "${#gone[@]}" -gt 0 ] || { emit W "apt would not remove anything"; finish ok; }
  [ "$refused" = 1 ] && finish ok
  log="$(DEBIAN_FRONTEND=noninteractive "$PREFIX/bin/apt-get" -y -q remove $auto "$@" 2>&1)" || emit W "apt: $(printf '%s\n' "$log" | grep -E '^(E|W):' | tail -n 1)"
  for n in "${!gone[@]}"; do
    if "$PREFIX/bin/dpkg-query" -W -f='${Status}\n' "$n" 2>/dev/null | grep -q ' installed$'; then
      result "$n" FAILED "${PKG_SIZE[$n]:-0}" "${PKG_SIZE[$n]:-0}" "" "still installed"
    else
      result "$n" REMOVED "${PKG_SIZE[$n]:-0}" 0 "" ""
    fi
  done
  finish ok
}

# ---------------------------------------------------------------- whole distributions and chosen folders

# Removes one proot distribution, the way proot-distro does, when it isn't running.
distro_remove() {
  local r="${1:-}" known="" c name="" before after
  emit V 1 "$HOME" "$PREFIX" 0
  while IFS= read -r -d '' c; do [ "$c" = "$r" ] && rootfs_allowed "$c" && known=1; done < <(list_rootfs)
  if [ -z "$known" ]; then result distro SKIP_UNSAFE 0 0 "$r" "not a known proot distribution"; finish ok; fi
  if proot_running; then result distro SKIP_ACTIVE 0 0 "$r" "a proot distribution is running"; finish ok; fi
  before="$(size_now "$r")"
  case "$r" in
    "$PREFIX"/var/lib/proot-distro/installed-rootfs/*) name="${r##*/}" ;;
    "$PREFIX"/var/lib/proot-distro/containers/*/rootfs) name="${r%/rootfs}"; name="${name##*/}" ;;
  esac
  if [ -n "$name" ] && [ -x "$PREFIX/bin/proot-distro" ]; then
    "$PREFIX/bin/proot-distro" remove "$name" < /dev/null > /dev/null 2>&1
  fi
  # Distros keep read-only files; make them writable, as proot-distro does, before removing what is left.
  if [ -e "$r" ] && dir_beneath "$r" "$FILES"; then
    chmod -R u+rwX -- "$r" 2>/dev/null
    rm -rf -- "$r" 2>/dev/null
  fi
  if [ -e "$r" ]; then
    after="$(size_now "$r")"
    result distro PARTIAL "$before" "$after" "$r" "some files could not be removed"
  else
    result distro CLEARED "$before" 0 "$r" "${name:-distribution} removed"
  fi
  finish ok
}

size_now() { du -x -sk -- "$1" 2>/dev/null | awk '{ print $1 * 1024; exit }'; }

# Packages that installed anything at or below PATH, comma separated (empty when none).
pkg_owners() {
  local p="$1" dir="$PREFIX/var/lib/dpkg/info"
  [ -d "$dir" ] || return 0
  { grep -lFx -- "$p" "$dir"/*.list; grep -lF -- "$p/" "$dir"/*.list; } 2>/dev/null |
    sed 's#.*/##; s#\.list$##; s#:.*##' | sort -u | head -n 6 | paste -sd, -
}

# Where inside a distribution you may delete: your data and add-ons, not its system (remove the whole distribution
# for that).
DISTRO_FREE='^(opt|root|home|tmp|var/tmp|var/cache|srv|usr/local)/.+'

# The first private key or keystore below DIR, if any. Certificates (.pem, .der) don't count: decompiled apps are full
# of them, and they are public. With "app", DIR is a decompiled app: keystores the app itself ships (in its assets,
# resources, code or libraries) came out of its APK and are no one's secret, so only keys outside those count.
private_key_in() {
  if [ "${2:-}" = app ]; then
    find "$1" -xdev -maxdepth 8 \( -name assets -o -name res -o -name resources -o -name sources -o -name 'smali*' \
      -o -name original -o -name unknown -o -name lib -o -name kotlin -o -name META-INF -o -name build \) -prune -o \
      -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' -o -name '*.kdbx' -o -name 'id_rsa' \
      -o -name 'id_ed25519' -o -name 'id_ecdsa' -o -name '.env' \) -print -quit 2>/dev/null
    return
  fi
  find "$1" -xdev -maxdepth 8 -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' \
    -o -name '*.kdbx' -o -name 'id_rsa' -o -name 'id_ed25519' -o -name 'id_ecdsa' -o -name '.env' \) -print -quit 2>/dev/null
}

# DIR itself, or the folder above it, is the top of a decompiled app (apktool or jadx output).
decompiled_root() {
  local d="$1"
  while [ -n "$d" ] && [ "$d" != "$HOME" ] && [ "$d" != / ]; do
    if [ -f "$d/apktool.yml" ] || { [ -f "$d/resources/AndroidManifest.xml" ] && [ -d "$d/sources" ]; }; then return 0; fi
    d="${d%/*}"
  done
  return 1
}

delete_one() {
  local p="$1" rel r c owners before after key inside=""
  if has_controls "$p"; then result path SKIP_UNSAFE 0 0 "" "control characters"; return; fi
  if [ ! -e "$p" ] && [ ! -L "$p" ]; then result path NO_CHANGE 0 0 "$p" "already gone"; return; fi
  if key="$(kept_why "$p")"; then result path SKIP_KEPT 0 0 "$p" "$key"; return; fi
  while IFS= read -r -d '' c; do
    case "$p" in "$c"/*) rootfs_allowed "$c" && inside="$c" ;; esac
  done < <(list_rootfs)
  if [ -n "$inside" ]; then
    rel="${p#"$inside"/}"
    if ! beneath "$p" "$inside" || ! [[ "$rel" =~ $DISTRO_FREE ]]; then
      result path SKIP_UNSAFE 0 0 "$p" "part of the distribution's system; remove the whole distribution instead"; return
    fi
    if proot_running; then result path SKIP_ACTIVE 0 0 "$p" "a proot distribution is running"; return; fi
  elif beneath "$p" "$HOME"; then
    rel="${p#"$HOME"/}"
    case "$rel" in
      storage|storage/*|.termux|.termux/*|.ssh|.ssh/*|.gnupg|.gnupg/*)
        result path SKIP_UNSAFE 0 0 "$p" "Termux or your keys need it"; return ;;
    esac
  elif beneath "$p" "$PREFIX"; then
    case "$p" in
      "$PREFIX"/var/lib/proot-distro|"$PREFIX"/var/lib/proot-distro/installed-rootfs*|"$PREFIX"/var/lib/proot-distro/containers*)
        result path SKIP_UNSAFE 0 0 "$p" "remove whole distributions under Linux distributions"; return ;;
    esac
    owners="$(pkg_owners "$p")"
    if [ -n "$owners" ]; then result path SKIP_PACKAGE 0 0 "$p" "$owners"; return; fi
  else
    result path SKIP_UNSAFE 0 0 "$p" "outside Termux or through a symlink"; return
  fi
  if [ -d "$p" ]; then
    if decompiled_root "$p"; then key="$(private_key_in "$p" app)"; else key="$(private_key_in "$p")"; fi
    if [ -n "$key" ]; then result path SKIP_KEYS 0 0 "$p" "holds ${key##*/}"; return; fi
  fi
  if [ -f "$p" ] && [[ "${p##*/}" =~ \.(jks|keystore|p12|pem|key|kdbx)$|^(id_rsa|id_ed25519|\.env) ]]; then
    result path SKIP_KEYS 0 0 "$p" "a key or credential"; return
  fi
  before="$(size_now "$p")"
  [ -d "$p" ] && chmod -R u+rwX -- "$p" 2>/dev/null
  rm -rf -- "$p" 2>/dev/null
  if [ -e "$p" ]; then
    after="$(size_now "$p")"
    result path PARTIAL "$before" "$after" "$p" "some files could not be removed"
  else
    result path CLEARED "$before" 0 "$p" ""
  fi
}

delete_paths() {
  local p
  emit V 1 "$HOME" "$PREFIX" 0
  load_keep
  for p in "$@"; do delete_one "$p"; done
  finish ok
}

# ---------------------------------------------------------------- Git repositories

# Git with nothing from the repository's own config able to run a program: these are read-only questions, and a repo
# unpacked from a download could carry a hostile config.
git_q() {
  local r="$1"
  shift
  git -c safe.directory="$r" -c core.fsmonitor=false -C "$r" "$@" 2>/dev/null
}

# A repository the steward may look at and pack: in the home, in $PREFIX/opt, in a distribution's home, root or opt,
# or in shared storage.
repo_root_ok() {
  local r="$1" rel base
  has_controls "$r" && return 1
  [ -d "$r/.git" ] && [ ! -L "$r/.git" ] || return 1
  case "$r" in
    "$SHARED"/*)
      rel="${r#"$SHARED"/}"
      case "/$rel/" in */../*|*/./*|*//*) return 1 ;; esac
      case "$rel" in Android/*|.*) return 1 ;; esac
      [ ! -L "$r" ] && return 0
      return 1
      ;;
  esac
  dir_beneath "$r" "$HOME" && { rel="${r#"$HOME"/}"; case "$rel" in storage|storage/*) return 1 ;; esac; return 0; }
  dir_beneath "$r" "$PREFIX/opt" && return 0
  base="$(rootfs_of "$r")" || return 1
  [[ "${r#"$base"/}" =~ ^(root|home|opt|srv|usr/local)/ ]]
}

find_repos() {
  local r
  {
    find "$HOME" -xdev -mindepth 2 -maxdepth 6 \( -path "$HOME/storage" -o -name node_modules -o -path "$HOME/.cache" \
      -o -path "$HOME/.local/share" -o -path "$HOME/.proot-distro" -o -path "$HOME/proot-distro" \) -prune -o -name .git -type d -print0 -prune 2>/dev/null
    [ -d "$PREFIX/opt" ] && find "$PREFIX/opt" -xdev -mindepth 2 -maxdepth 4 -name .git -type d -print0 -prune 2>/dev/null
    while IFS= read -r -d '' r; do
      rootfs_allowed "$r" || continue
      find "$r/root" "$r/home" "$r/opt" -xdev -mindepth 2 -maxdepth 5 -name node_modules -prune -o -name .git -type d -print0 -prune 2>/dev/null
    done < <(list_rootfs | sort -zu)
    [ -d "$SHARED" ] && find "$SHARED/" -mindepth 2 -maxdepth 5 \( -path "$SHARED/Android" -o -name node_modules \) -prune -o \
      -name .git -type d -print0 -prune 2>/dev/null
  } | sort -zu
}

kib_field() { awk -v k="$1" -F': ' '$1 == k { print $2 * 1024; f = 1 } END { if (!f) print 0 }'; }

# G, path, bytes (-1 in shared storage: the app knows), .git bytes, loose, garbage, last commit, last fetch, last local
# Git activity, shallow, uncommitted changes (-1 when not checked), commits not pushed (-1 without an upstream),
# branch, remote (credentials stripped).
repos() {
  local g r bytes gbytes counts loose garbage commit fetch active shallow dirty unpushed branch remote f t
  emit V 1 "$HOME" "$PREFIX" 0
  command -v git > /dev/null 2>&1 || refuse "git is not installed"
  while IFS= read -r -d '' g; do
    r="${g%/.git}"
    repo_root_ok "$r" || continue
    case "$r" in "$SHARED"/*) bytes=-1 ;; *) bytes="$(size_now "$r")" ;; esac
    gbytes="$(size_now "$g")"
    counts="$(git_q "$r" count-objects -v)"
    loose="$(printf '%s\n' "$counts" | kib_field size)"
    garbage="$(printf '%s\n' "$counts" | kib_field size-garbage)"
    commit="$(git_q "$r" log -1 --format=%ct)"
    fetch="$(stat -c %Y -- "$g/FETCH_HEAD" 2>/dev/null || echo 0)"
    active=0
    for f in "$g/index" "$g/HEAD" "$g/logs/HEAD" "$g/ORIG_HEAD"; do
      t="$(stat -c %Y -- "$f" 2>/dev/null || echo 0)"
      [ "$t" -gt "$active" ] && active="$t"
    done
    shallow=0; [ -f "$g/shallow" ] && shallow=1
    # Checking for changes reads every tracked file; not through shared storage's slow layer, and not in big trees.
    dirty=-1
    case "$r" in "$SHARED"/*) ;; *)
      if [ "$(git_q "$r" ls-files | head -n 50001 | wc -l)" -le 50000 ]; then
        dirty=0
        [ -n "$(git_q "$r" status --porcelain --untracked-files=no | head -n 1)" ] && dirty=1
      fi ;;
    esac
    unpushed="$(git_q "$r" rev-list --count '@{upstream}..HEAD')"
    branch="$(git_q "$r" symbolic-ref --short -q HEAD)"
    remote="$(git_q "$r" config --get remote.origin.url | sed -E 's#(://)[^/@]*@#\1#' | head -n 1)"
    has_controls "$branch$remote" && continue
    emit G "$r" "$bytes" "$gbytes" "${loose:-0}" "${garbage:-0}" "${commit:-0}" "$fetch" "$active" "$shallow" "$dirty" "${unpushed:--1}" "$branch" "$remote"
  done < <(find_repos)
  finish ok
}

# git gc: packs loose objects and old packs into one. The repository works exactly as before (lossless).
git_gc() {
  local r g before after log
  emit V 1 "$HOME" "$PREFIX" 0
  command -v git > /dev/null 2>&1 || refuse "git is not installed"
  for r in "$@"; do
    g="$r/.git"
    if ! repo_root_ok "$r"; then result gc SKIP_UNSAFE 0 0 "$r" "not a repository the steward may pack"; continue; fi
    if rootfs_of "$r" > /dev/null && proot_running; then result gc SKIP_ACTIVE 0 0 "$r" "a proot distribution is running"; continue; fi
    if [ -f "$g/index.lock" ] || [ -f "$g/gc.pid" ]; then result gc SKIP_ACTIVE 0 0 "$r" "Git is working in it right now"; continue; fi
    before="$(size_now "$g")"
    log="$(git -c safe.directory="$r" -C "$r" gc --quiet 2>&1 < /dev/null)" || emit W "git gc ${r##*/}: $(printf '%s\n' "$log" | tail -n 1)"
    after="$(size_now "$g")"
    if [ "${after:-0}" -lt "${before:-0}" ]; then result gc CLEARED "$before" "$after" "$r" "packed"
    else result gc NO_CHANGE "$before" "${after:-0}" "$r" "already packed"; fi
  done
  finish ok
}

# ---------------------------------------------------------------- moving projects out of shared storage

# A folder in shared storage the steward may move into Termux: not a top-level folder, not app-owned, no symlinks.
shared_dir_ok() {
  local p="$1" rel
  has_controls "$p" && return 1
  case "$p" in "$SHARED"/*/*) rel="${p#"$SHARED"/}" ;; *) return 1 ;; esac
  case "/$rel/" in */../*|*/./*|*//*) return 1 ;; esac
  case "$rel" in Android/*|.*|DCIM/*|*/) return 1 ;; esac
  [ -d "$p" ] && [ ! -L "$p" ]
}

# Same files, sizes and bytes in A and B.
same_tree() {
  local a="$1" b="$2"
  [ "$(cd -- "$a" && find . -type f -printf '%P\t%s\n' | sort | cksum)" = "$(cd -- "$b" && find . -type f -printf '%P\t%s\n' | sort | cksum)" ] || return 1
  [ "$(cd -- "$a" && find . -type f -print0 | sort -z | xargs -0 -r cat -- | cksum)" = "$(cd -- "$b" && find . -type f -print0 | sort -z | xargs -0 -r cat -- | cksum)" ]
}

# Copies SRC to DEST through a temporary name, checks every byte, and only then removes SRC. Nothing is lost when
# anything fails: the copy goes, the original stays.
copy_verify_move() {
  local src="$1" dest="$2" tmp before free
  before="$(size_now "$src")"
  free="$(df -Pk -- "${dest%/*}" 2>/dev/null | awk 'NR == 2 { print $4 * 1024 }')"
  if [ -n "$free" ] && [ "$free" -lt $(( before + before / 10 + 67108864 )) ]; then
    result move SKIP_SPACE "$before" "$before" "$src" "not enough free space"; return
  fi
  tmp="${dest%/*}/.steward-partial-${dest##*/}"
  rm -rf -- "$tmp" 2>/dev/null
  if ! cp -R --preserve=timestamps -- "$src" "$tmp" 2>/dev/null || ! same_tree "$src" "$tmp"; then
    rm -rf -- "$tmp" 2>/dev/null
    result move FAILED "$before" "$before" "$src" "the copy didn't match; nothing was moved"; return
  fi
  mv -- "$tmp" "$dest" || { rm -rf -- "$tmp"; result move FAILED "$before" "$before" "$src" "couldn't name the copy"; return; }
  rm -rf -- "$src" 2>/dev/null
  if [ -e "$src" ]; then result move PARTIAL "$before" "$before" "$src" "$dest"
  else result move MOVED "$before" 0 "$src" "$dest"; fi
}

# A project in shared storage into ~/projects: tools, Git and scans get it without the shared-storage layer.
relocate() {
  local src="${1:-}" name dest
  emit V 1 "$HOME" "$PREFIX" 0
  shared_dir_ok "$src" || { result move SKIP_UNSAFE 0 0 "$src" "not a folder in shared storage the steward may move"; finish ok; }
  name="${src##*/}"
  dest="$HOME/projects/$name"
  if [ -e "$dest" ] || [ -L "$dest" ]; then result move SKIP_EXISTS 0 0 "$src" "$dest already exists"; finish ok; fi
  mkdir -p -- "$HOME/projects" && dir_beneath "$HOME/projects" "$HOME" || { result move SKIP_UNSAFE 0 0 "$src" "~/projects is not a plain folder"; finish ok; }
  copy_verify_move "$src" "$dest"
  finish ok
}

# Back again: a folder relocate made, to where it came from.
relocate_back() {
  local src="${1:-}" dest="${2:-}" why
  emit V 1 "$HOME" "$PREFIX" 0
  case "$src" in "$HOME"/projects/*) ;; *) result move SKIP_UNSAFE 0 0 "$src" "not a folder in ~/projects"; finish ok ;; esac
  case "${src#"$HOME"/projects/}" in */*|.*) result move SKIP_UNSAFE 0 0 "$src" "not a folder in ~/projects"; finish ok ;; esac
  dir_beneath "$src" "$HOME" || { result move SKIP_UNSAFE 0 0 "$src" "missing or symlinked"; finish ok; }
  load_keep
  if why="$(kept_why "$src")"; then result move SKIP_KEPT 0 0 "$src" "$why"; finish ok; fi
  case "$dest" in "$SHARED"/*/*) ;; *) result move SKIP_UNSAFE 0 0 "$src" "not a place in shared storage"; finish ok ;; esac
  has_controls "$dest" && { result move SKIP_UNSAFE 0 0 "$src" "unsafe name"; finish ok; }
  case "/${dest#"$SHARED"/}/" in */../*|*/./*|*//*|/Android/*) result move SKIP_UNSAFE 0 0 "$src" "not a place in shared storage"; finish ok ;; esac
  if [ -e "$dest" ]; then result move SKIP_EXISTS 0 0 "$src" "$dest already exists"; finish ok; fi
  [ -d "${dest%/*}" ] || mkdir -p -- "${dest%/*}" 2>/dev/null
  copy_verify_move "$src" "$dest"
  finish ok
}

case "$MODE" in
  audit) audit ;;
  clean) clean "$@" ;;
  packages) packages ;;
  pkg-plan) pkg_plan "$@" ;;
  pkg-remove) pkg_remove "$@" ;;
  distro-remove) distro_remove "$@" ;;
  delete) delete_paths "$@" ;;
  prog-remove) prog_remove "$@" ;;
  repos) repos ;;
  git-gc) git_gc "$@" ;;
  relocate) relocate "$@" ;;
  relocate-back) relocate_back "$@" ;;
  keep|unkeep) keep_edit "$MODE" "$@" ;;
  *) refuse "unknown mode: $MODE" ;;
esac

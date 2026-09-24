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
  emit E "$1"
  exec 3>&-
  [ -n "$OUT" ] && printf 'O\t%s\n' "$OUT"
  exit "${2:-0}"
}

refuse() {
  emit X refused "$1"
  finish refused 2
}

# ---------------------------------------------------------------- context

[ -n "${HOME:-}" ] && [ -n "${PREFIX:-}" ] || refuse "HOME and PREFIX must be set: run this inside Termux"
has_controls "$HOME$PREFIX" && refuse "unsafe environment"
[ -d "$HOME" ] && [ ! -L "$HOME" ] && [ -d "$PREFIX" ] && [ ! -L "$PREFIX" ] || refuse "HOME or PREFIX is missing or a symlink"
FILES="${HOME%/home}"
[ "$FILES" != "$HOME" ] && [ "$PREFIX" = "$FILES/usr" ] || refuse "not a Termux layout"
APPDIR="${FILES%/files}"
if [ "${STEWARD_TERMUX_FIXTURE:-0}" = "1" ]; then
  [ "$(cat -- "$APPDIR/.steward-termux-fixture" 2>/dev/null)" = "DISPOSABLE-FIXTURE" ] || refuse "fixture marker missing"
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
    *) find "$p" -xdev -type f -printf '%s\n' 2>/dev/null | sum_sizes ;;
  esac
}

# Versions a self-updating tool (Claude Code) keeps next to the one in use: every entry of P except the newest and the
# one ~/.local/bin/claude points to. Nothing, unless that link shows which version is in use.
old_versions() {
  local p="$1" used newest e
  used="$(readlink -f -- "$HOME/.local/bin/claude" 2>/dev/null)" || return 0
  case "$used" in "$p"/*) ;; *) return 0 ;; esac
  newest="$(find "$p" -mindepth 1 -maxdepth 1 -printf '%T@\t%f\n' 2>/dev/null | sort -rn | head -n 1 | cut -f2)"
  for e in "$p"/*; do
    [ -e "$e" ] && [ ! -L "$e" ] || continue
    [ "${e##*/}" = "$newest" ] && continue
    case "$used" in "$e"|"$e"/*) continue ;; esac
    printf '%s\0' "$e"
  done
}

# Python bytecode caches (__pycache__) below P, outside shared storage, Git internals and proot distributions.
# Python ignores a cached .pyc whose source is gone and rebuilds the rest on the next import (PEP 3147).
pycache_dirs() {
  find "$1" -xdev \( -path "$HOME/storage" -o -name .git -o -iname '*-rootfs' -o -iname '*-fs' -o -name installed-rootfs \) -prune \
    -o -type d -name __pycache__ -print0 -prune 2>/dev/null
}

# ---------------------------------------------------------------- known targets

FIXED_IDS="apt-archives apt-pkgcache termux-tmp var-tmp termux-var-log proot-dlcache trash npm-logs termux-app-cache npm-cache npx-cache pip-cache uv-cache poetry-cache pycache yarn-cache yarn-berry-cache go-build go-mod-download cargo-registry-cache cargo-registry-src cargo-registry-index cargo-git-db cargo-git-checkouts rustup-downloads rustup-tmp bun-cache android-cache gradle-daemon-logs gradle-caches claude-versions koa-archives apt-lists"

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
    koa-archives) echo "$HOME/.storage-autopilot-archives|$HOME|contents" ;;
    gradle-caches) echo "$HOME/.gradle/caches|$HOME|contents" ;;
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

# ---------------------------------------------------------------- audit

# Sizes (KiB) of everything of 1 MiB or more below the Termux files folder, from one walk. Earlier versions walked
# the whole tree four or five times (du per level, per distro, then find for large files): about six minutes on a
# real phone with proot distributions installed.
declare -A BIG=()
load_sizes() {
  local k p
  while IFS=$'\t' read -r k p; do
    has_controls "$p" || BIG["$p"]="$k"
  done < <(du -x -a -k -t 1M -- "$FILES" 2>/dev/null)
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
  load_sizes

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

  for id in $FIXED_IDS; do
    info="$(target_info "$id")" || continue
    IFS='|' read -r p root mode <<< "$info"
    [ -e "$p" ] || [ -L "$p" ] || continue
    if ! dir_beneath "$p" "$root"; then emit X "$id" "$p" "symlinked or outside the Termux folders"; continue; fi
    m="$(measure "$mode" "$p")"
    bytes="${m%%$'\t'*}"; files="${m#*$'\t'}"
    [ "${files:-0}" -gt 0 ] && emit T "$id" "$bytes" "$files" "$p"
  done

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

  # The size map itself, for browsing Termux folder by folder in the app: only into the --out file, because the
  # result bundle that carries stdout is too small for it.
  if [ -n "$OUT" ]; then
    for p in "${!BIG[@]}"; do
      [ -L "$p" ] && continue
      if [ -d "$p" ]; then emit S "$(( ${BIG[$p]} * 1024 ))" d "$p"; elif [ -f "$p" ]; then emit S "$(( ${BIG[$p]} * 1024 ))" f "$p"; fi
    done
  fi

  # Largest files anywhere in Termux (report only), from the same walk.
  for p in "${!BIG[@]}"; do
    [ -f "$p" ] && [ ! -L "$p" ] && printf '%s\t%s\n' "${BIG[$p]}" "$p"
  done | sort -t "$(printf '\t')" -k1,1nr | head -n 30 | while IFS=$'\t' read -r k p; do
    emit L "$(stat -c %s -- "$p" 2>/dev/null || echo $(( k * 1024 )))" "$(stat -c %Y -- "$p" 2>/dev/null || echo 0)" "$p"
  done

  finish ok
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
    oldversions)
      dir_beneath "$p" "$root" || return 1
      while IFS= read -r -d '' e; do
        beneath "$e" "$p" && rm -rf -- "$e"
      done < <(old_versions "$p")
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
  local spec="$1" id p info root mode before after repo r suffix
  id="${spec%%=*}"
  p=""
  [ "$id" != "$spec" ] && p="${spec#*=}"
  if has_controls "$spec"; then result "$id" SKIP_UNSAFE 0 0 "" "control characters"; return; fi

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

clean() {
  local spec
  emit V 1 "$HOME" "$PREFIX" 0
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

packages() {
  local name size protected version deps summary m
  declare -A manual=()
  emit V 1 "$HOME" "$PREFIX" 0
  [ -x "$PREFIX/bin/dpkg-query" ] || refuse "dpkg-query is missing"
  while IFS= read -r name; do manual["$name"]=1; done < <("$PREFIX/bin/apt-mark" showmanual 2>/dev/null)
  while IFS="$US" read -r name size protected version deps summary; do
    has_controls "$version$deps$summary" && continue
    m=0; [ -n "${manual[$name]:-}" ] && m=1
    emit K "$name" "$size" "$m" "$protected" "$version" "$deps" "$summary"
  done < <(load_packages)
  finish ok
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
  for n in "$@"; do want["$n"]=1; done
  if ! err="$(simulate_remove "$@" 2>&1 >/dev/null)"; then emit W "apt refused: $err"; finish ok; fi
  while IFS= read -r n; do base["$n"]=1; done < <(simulate_remove "$@" 2>/dev/null)
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    if [ -n "${want[$n]:-}" ]; then kind=requested; elif [ -n "${base[$n]:-}" ]; then kind=dependent; else kind=orphan; fi
    emit P "$n" "${PKG_SIZE[$n]:-0}" "$kind" "${PKG_PROTECTED[$n]:-0}"
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
  # Checked again here: the dry run decides, and one protected package stops the whole removal.
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    gone["$n"]=1
    if [ "${PKG_PROTECTED[$n]:-0}" = 1 ]; then result "$n" SKIP_PROTECTED 0 0 "" "Termux needs it"; refused=1; fi
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
# of them, and they are public.
private_key_in() {
  find "$1" -xdev -maxdepth 8 -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' \
    -o -name '*.kdbx' -o -name 'id_rsa' -o -name 'id_ed25519' -o -name 'id_ecdsa' -o -name '.env' \) -print -quit 2>/dev/null
}

delete_one() {
  local p="$1" rel r c owners before after key inside=""
  if has_controls "$p"; then result path SKIP_UNSAFE 0 0 "" "control characters"; return; fi
  if [ ! -e "$p" ] && [ ! -L "$p" ]; then result path NO_CHANGE 0 0 "$p" "already gone"; return; fi
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
    key="$(private_key_in "$p")"
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
  for p in "$@"; do delete_one "$p"; done
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
  *) refuse "unknown mode: $MODE" ;;
esac

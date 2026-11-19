#!/usr/bin/env bash
# One-time setup of the Galaxy Steward release signing key. Runs in Termux, or in any Linux or macOS shell that has
# a JDK and the GitHub CLI.
#
#   curl -fsSLO https://raw.githubusercontent.com/Zfkirke0109/Galaxy-Filesystem-Steward/main/scripts/setup-signing.sh
#   bash setup-signing.sh [owner/repo]
#
# It creates a signing key on this device, stores it in the repository's encrypted GitHub Actions secrets, and pins
# its fingerprint in a repository variable. From then on every CI build is signed with this key, so each new APK
# installs as an update over the last one.
#
# Running it again reuses the key (a new key could not update the installed app) and uploads the secrets again.
# Set GALAXY_STEWARD_KEY_DIR to keep the key somewhere other than ~/.galaxy-steward.
set -euo pipefail

repo=${1:-Zfkirke0109/Galaxy-Filesystem-Steward}
dir=${GALAXY_STEWARD_KEY_DIR:-$HOME/.galaxy-steward}
keystore=$dir/release.jks
password_file=$dir/release.password
key_alias=galaxy-steward

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() {
    printf '\n\033[31mError:\033[0m %s\n' "$*" >&2
    exit 1
}
normalize() { tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }

# 1. Tools. In Termux they are installed on demand; elsewhere they must already be there.
if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == */com.termux/* ]]; then
    if ! command -v keytool >/dev/null; then
        say "Installing a JDK for keytool"
        pkg install -y openjdk-21 || pkg install -y openjdk-17
    fi
    if ! command -v gh >/dev/null; then
        say "Installing the GitHub CLI"
        pkg install -y gh
    fi
fi
command -v keytool >/dev/null || die "keytool not found. Install a JDK (17 or later)."
command -v gh >/dev/null || die "the GitHub CLI (gh) is not installed. See https://cli.github.com"

# 2. GitHub login, with the rights to set this repository's secrets.
if ! gh auth status >/dev/null 2>&1; then
    say "Log in to GitHub"
    gh auth login --git-protocol https --web
fi
gh repo view "$repo" --json name >/dev/null || die "this GitHub login can't see $repo"

# The fingerprint of the key this repository's builds are already signed with, if any.
pinned=$(gh variable get GALAXY_STEWARD_CERT_SHA256 --repo "$repo" 2>/dev/null | normalize || true)
restore_hint="Copy release.jks and release.password from your backup into $dir and run this again.
Only if that key is lost for good, run with FORCE=1 to switch keys; the installed app then has to be uninstalled once."

# 3. The key. Never replaced once it exists.
mkdir -p "$dir"
chmod 700 "$dir"
if [ -f "$keystore" ]; then
    say "Using the existing key in $keystore"
    [ -s "$password_file" ] || die "$password_file is missing. Restore it from your backup next to $keystore."
    password=$(cat "$password_file")
else
    if [ -n "$pinned" ] && [ "${FORCE:-}" != 1 ]; then
        die "$repo already has a signing key (SHA-256 $pinned), and it isn't on this device.
$restore_hint"
    fi
    say "Creating a new release key in $keystore"
    password=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c 1-32)
    [ ${#password} -ge 24 ] || die "couldn't generate a password"
    (
        umask 077
        printf '%s\n' "$password" >"$password_file"
    )
    STOREPASS=$password keytool -genkeypair -keystore "$keystore" -storetype PKCS12 \
        -storepass:env STOREPASS -keypass:env STOREPASS -alias "$key_alias" \
        -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Galaxy Steward" >/dev/null
    chmod 600 "$keystore"
fi

listing=$(STOREPASS=$password keytool -list -v -keystore "$keystore" -storepass:env STOREPASS -alias "$key_alias") ||
    die "can't open $keystore with the password in $password_file, or it has no key named $key_alias"
fingerprint=$(awk '$1 == "SHA256:" { print $2; exit }' <<<"$listing" | normalize)
[ ${#fingerprint} -eq 64 ] || die "couldn't read the key's SHA-256 fingerprint"

# 4. Refuse to silently swap the key of a repository that already pins another one.
if [ -n "$pinned" ] && [ "$pinned" != "$fingerprint" ] && [ "${FORCE:-}" != 1 ]; then
    die "$repo already uses a different signing key (SHA-256 $pinned), not the one in $keystore.
$restore_hint"
fi

# 5. Secrets (encrypted on this device before upload) and the pinned fingerprint.
say "Storing the key in $repo's GitHub Actions secrets"
base64 <"$keystore" | tr -d '\n' | gh secret set GALAXY_STEWARD_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$password" | gh secret set GALAXY_STEWARD_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$key_alias" | gh secret set GALAXY_STEWARD_KEY_ALIAS --repo "$repo"
gh variable set GALAXY_STEWARD_CERT_SHA256 --repo "$repo" --body "$fingerprint"

# 6. A signed build of main right away, if main's workflow already knows about the key.
workflow=$(gh api "repos/$repo/contents/.github/workflows/android.yml?ref=main" --jq .content 2>/dev/null | base64 -d 2>/dev/null || true)
if grep -q GALAXY_STEWARD_KEYSTORE_BASE64 <<<"$workflow"; then
    if gh workflow run android.yml --repo "$repo" --ref main >/dev/null 2>&1; then
        say "Started a signed build of main"
        echo "When it finishes, the APK is at https://github.com/$repo/releases/latest/download/galaxy-steward.apk"
    fi
else
    say "Merge the pull request that adds release signing; its build on main will be the first signed release."
fi

cat <<EOF

Every build of $repo is now signed with this key:
  SHA-256 $fingerprint

Back up these two files somewhere private, such as a password manager or your own cloud drive (not shared storage,
where other apps can read them):
  $keystore
  $password_file
GitHub can use the secrets but will never show them again. Without this key, a future build can't update the
installed app: it would have to be uninstalled first, which deletes its settings and history.
EOF

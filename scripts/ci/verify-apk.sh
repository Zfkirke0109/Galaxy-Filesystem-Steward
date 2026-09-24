#!/usr/bin/env bash
# Checks how the release APK is signed and stages it for upload as dist/galaxy-steward-<version>.apk.
#
# Usage: scripts/ci/verify-apk.sh <apk> <out-dir>
#
# Environment:
#   RELEASE_SIGNED        "true" when the build was given the release key. The APK must then carry a single
#                         non-debug certificate.
#   EXPECTED_CERT_SHA256  Optional pinned certificate fingerprint (repository variable GALAXY_STEWARD_CERT_SHA256).
#                         Colons and case are ignored. A different certificate fails the build, because phones that
#                         have the app would refuse the update.
#   ANDROID_HOME          Android SDK with build-tools (apksigner, aapt2).
#
# Writes version, version_code, cert_sha256, apk and apk_sha256 to $GITHUB_OUTPUT, and a summary to
# $GITHUB_STEP_SUMMARY, when those are set (as they are in GitHub Actions).
set -euo pipefail

apk=${1:?usage: verify-apk.sh <apk> <out-dir>}
out_dir=${2:?usage: verify-apk.sh <apk> <out-dir>}

fail() {
    echo "::error title=$1::$2"
    exit 1
}

normalize() { tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }

[ -f "$apk" ] || fail "APK missing" "$apk was not built"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || fail "No Android SDK" "ANDROID_HOME is not set"
tools=$(printf '%s\n' "$sdk"/build-tools/*/ | sort -V | tail -n 1)
tools=${tools%/}
[ -x "$tools/apksigner" ] && [ -x "$tools/aapt2" ] || fail "No build tools" "apksigner or aapt2 not found in $sdk/build-tools"

# apksigner exits non-zero when any signature is invalid.
certs=$("$tools/apksigner" verify --verbose --print-certs "$apk") || fail "Invalid signature" "apksigner could not verify $apk"
grep -q '^Number of signers: 1$' <<<"$certs" || fail "Unexpected signers" "the APK must have exactly one signer"
grep -Eq '^Verified using v(2|3) scheme .*: true$' <<<"$certs" || fail "Old signature scheme" "the APK has no v2 or v3 signature"
# Up to build-tools 36 the lines read "Signer #1 certificate ...", from 37 "V2 Signer: certificate ..." (one set per
# scheme). Every one of them must name the same certificate.
signer='^(Signer #[0-9]+|V[0-9.]+ Signer:) certificate'
dns=$(sed -nE "s/$signer DN: //p" <<<"$certs" | sort -u)
cert_shas=$(sed -nE "s/$signer SHA-256 digest: //p" <<<"$certs" | tr -d ': \t\r' | tr 'A-F' 'a-f' | sort -u)
[ -n "$cert_shas" ] || fail "No certificate" "could not read the signing certificate's SHA-256 digest"
[ "$(wc -l <<<"$cert_shas")" -eq 1 ] || fail "Several certificates" "the APK's signatures name more than one certificate"
cert_sha=$cert_shas
[[ "$cert_sha" =~ ^[0-9a-f]{64}$ ]] || fail "No certificate" "could not read the signing certificate's SHA-256 digest"

debug_key=false
case "$dns" in *"CN=Android Debug"*) debug_key=true ;; esac

if [ "${RELEASE_SIGNED:-false}" = true ]; then
    [ "$debug_key" = false ] || fail "Debug certificate" "the build had the release key but the APK is signed with a debug certificate"
    expected=$(printf '%s' "${EXPECTED_CERT_SHA256:-}" | normalize)
    if [ -n "$expected" ] && [ "$expected" != "$cert_sha" ]; then
        fail "Signing key changed" "the APK is signed with certificate $cert_sha, but the repository variable GALAXY_STEWARD_CERT_SHA256 pins $expected. Phones with the app installed would refuse this update. Restore the original key in the GALAXY_STEWARD_KEYSTORE_* secrets (see docs/SIGNING.md)."
    fi
    [ -n "$expected" ] || echo "::notice title=Pin the signing key::Set the repository variable GALAXY_STEWARD_CERT_SHA256 to $cert_sha so a replaced key fails the build instead of publishing an APK that can't update the app."
    signed_with="release key"
else
    echo "::warning title=APK signed with a throwaway debug key::The GALAXY_STEWARD_KEYSTORE_* secrets are not available to this run (not set up yet, or a pull request from a fork). This APK can't be installed over a release build. See docs/SIGNING.md."
    signed_with="throwaway debug key (can't update a release install)"
fi

badging=$("$tools/aapt2" dump badging "$apk")
package_line=$(grep -m 1 '^package:' <<<"$badging")
version=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$package_line")
version_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$package_line")
[[ "$version" =~ ^[0-9A-Za-z.+-]+$ ]] || fail "No version" "could not read a usable versionName from the APK"

mkdir -p "$out_dir"
staged="$out_dir/galaxy-steward-$version.apk"
cp "$apk" "$staged"
apk_sha=$(sha256sum "$staged" | cut -d ' ' -f 1)

{
    echo "version=$version"
    echo "version_code=$version_code"
    echo "cert_sha256=$cert_sha"
    echo "apk=$staged"
    echo "apk_sha256=$apk_sha"
} >>"${GITHUB_OUTPUT:-/dev/stdout}"

summary="### Galaxy Steward $version

| | |
|---|---|
| Version | $version (code $version_code) |
| Signed with | $signed_with |
| Certificate SHA-256 | \`$cert_sha\` |
| APK SHA-256 | \`$apk_sha\` |
"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s' "$summary" >>"$GITHUB_STEP_SUMMARY"
else
    printf '%s' "$summary"
fi

#!/usr/bin/env bash
# D2: reproducible-build check. F-Droid rebuilds our tagged source and ships our signature only if its unsigned APK
# matches ours once the signature is set aside (Binaries + AllowedAPKSigningKeys, docs/RELEASING.md).
#
# The script copies the project into two separate folders (different absolute paths, like our machine and F-Droid's
# build server), runs a clean unsigned release build in each with no build cache and no keystore, and compares:
#   1. the whole APK files (sha256): unsigned APKs should be byte-identical;
#   2. if they differ, the unzipped contents (ignoring META-INF signature files) and the zip entry listing, so the
#      cause is visible.
# With --signed <apk> it also compares the first unsigned build with a signed release APK: `apksigcopier compare`
# when installed (pip install apksigcopier), else an unzip-and-diff that ignores the signature files.
#
# Usage: tools/repro-check.sh [--app phone|lists|both] [--signed path/to/signed.apk] [--keep] [extra gradle args...]
#   Example on a small machine:
#     tools/repro-check.sh -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g --max-workers=2
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHICH=both
SIGNED=""
KEEP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --app) WHICH="$2"; shift 2 ;;
        --signed) SIGNED="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        *) break ;;
    esac
done

TASKS=(); APKS=()
if [[ $WHICH == phone || $WHICH == both ]]; then
    TASKS+=(:app:assembleRelease); APKS+=(app/build/outputs/apk/release/app-release-unsigned.apk)
fi
if [[ $WHICH == lists || $WHICH == both ]]; then
    TASKS+=(:lists-updater:assembleRelease); APKS+=(lists-updater/build/outputs/apk/release/lists-updater-release-unsigned.apk)
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parley-repro.XXXXXX")"
cleanup() { if [[ $KEEP -eq 0 ]]; then rm -rf "$WORK"; else echo "Kept $WORK"; fi; }
trap cleanup EXIT

build_copy() {
    local dir="$1"; shift
    mkdir -p "$dir"
    tar -C "$ROOT" \
        --exclude='./build' --exclude='*/build' --exclude='./.gradle' --exclude='./.kotlin' --exclude='*/.kotlin' \
        --exclude='./keystore.properties' --exclude='*.jks' --exclude='*.keystore' --exclude='./dist' \
        -cf - . | tar -C "$dir" -xf -
    echo "==> Clean unsigned release build in $dir"
    (cd "$dir" && env -u PARLEY_KEYSTORE -u PARLEY_KEYSTORE_PASSWORD -u PARLEY_KEY_ALIAS -u PARLEY_KEY_PASSWORD \
        ./gradlew --no-build-cache -q "$@" "${TASKS[@]}")
}

# Unzips an APK without its signature files (v1 signature; v2/v3 live outside the zip entries).
unpack() {
    rm -rf "$2"; mkdir -p "$2"
    unzip -q -o "$1" -d "$2"
    rm -f "$2"/META-INF/*.SF "$2"/META-INF/*.RSA "$2"/META-INF/*.EC "$2"/META-INF/*.DSA "$2"/META-INF/MANIFEST.MF
}

build_copy "$WORK/first/parley-phone" "$@"
build_copy "$WORK/second-build-elsewhere/parley-phone" "$@"

status=0
for apk in "${APKS[@]}"; do
    a="$WORK/first/parley-phone/$apk"; b="$WORK/second-build-elsewhere/parley-phone/$apk"
    ha="$(sha256sum "$a" | cut -d' ' -f1)"; hb="$(sha256sum "$b" | cut -d' ' -f1)"
    echo "==> $(basename "$apk")"
    echo "    build 1: $ha"
    echo "    build 2: $hb"
    if [[ "$ha" == "$hb" ]]; then
        echo "    REPRODUCIBLE: byte-identical"
        continue
    fi
    status=1
    echo "    NOT REPRODUCIBLE. Differences:"
    unpack "$a" "$WORK/u1"; unpack "$b" "$WORK/u2"
    diff -rq "$WORK/u1" "$WORK/u2" | sed 's/^/      /' || true
    diff <(zipinfo -l "$a" | tail -n +3 | sed 's/  */ /g') <(zipinfo -l "$b" | tail -n +3 | sed 's/  */ /g') \
        | head -40 | sed 's/^/      zip: /' || true
done

if [[ -n "$SIGNED" ]]; then
    unsigned="$WORK/first/parley-phone/${APKS[0]}"
    echo "==> Signed $SIGNED vs unsigned $(basename "$unsigned")"
    if command -v apksigcopier > /dev/null; then
        if apksigcopier compare "$SIGNED" "$unsigned"; then echo "    MATCH (apksigcopier)"; else echo "    DIFFERENT (apksigcopier)"; status=1; fi
    else
        unpack "$SIGNED" "$WORK/s1"; unpack "$unsigned" "$WORK/s2"
        if diff -rq "$WORK/s1" "$WORK/s2"; then echo "    contents MATCH (install apksigcopier for the exact check)"
        else echo "    contents DIFFER"; status=1; fi
    fi
fi

exit $status

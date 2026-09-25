#!/usr/bin/env bash
# D1 (G1): check that both apps still build after F-Droid removes their signing configuration.
#
# Before it builds, F-Droid's `remove_signing_keys` deletes every `signingConfigs {` block and every line that starts
# with `signingConfig =` (fdroidserver common.py). If any line that survives still refers to the signing config, the
# F-Droid build fails with "SigningConfig with name 'release' not found".
#
# This script copies the project to a temporary folder (the working tree is never touched), strips the build files
# the way F-Droid does (with fdroidserver itself if it is installed, otherwise with a copy of its regexes), checks
# that no live reference to a signing config is left, and runs an unsigned release build with no keystore present.
#
# Usage: tools/fdroid-strip-check.sh [--static] [extra gradle args...]
#   --static   only strip and look for leftover references; don't build (a few seconds)
#   Default gradle tasks: :app:assembleRelease :lists-updater:assembleRelease -x lintVitalRelease
#   Example on a small machine:
#     tools/fdroid-strip-check.sh -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g --max-workers=2
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATIC=0
if [[ "${1:-}" == "--static" ]]; then STATIC=1; shift; fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parley-fdroid-strip.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> Copying the project to $WORK"
# Sources only: no build outputs, no Gradle state and never the keystore settings.
tar -C "$ROOT" \
    --exclude='./build' --exclude='*/build' --exclude='./.gradle' --exclude='./.kotlin' --exclude='*/.kotlin' \
    --exclude='./keystore.properties' --exclude='*.jks' --exclude='*.keystore' --exclude='./dist' \
    -cf - . | tar -C "$WORK" -xf -

echo "==> Stripping signing configs as F-Droid does"
python3 - "$WORK" <<'PY'
import os, re, sys

build_dir = sys.argv[1]
try:
    # The real thing when available (pip install fdroidserver).
    from fdroidserver.common import remove_signing_keys
    print("    using fdroidserver.common.remove_signing_keys")
except Exception:
    # Verbatim copy of fdroidserver 2.4.5 common.remove_signing_keys (gradle part).
    print("    fdroidserver not installed; using the bundled copy of its regexes")
    gradle_comment = re.compile(r'[ ]*//')
    gradle_signing_configs = re.compile(r'^[\t ]*signingConfigs[ \t]*{[ \t]*$')
    gradle_line_matches = [
        re.compile(r'^[\t ]*signingConfig\s*[= ]\s*[^ ]*$'),
        re.compile(r'.*android\.signingConfigs\.[^{]*$'),
        re.compile(r'.*release\.signingConfig *= *'),
    ]

    def remove_signing_keys(build_dir):
        for root, dirs, files in os.walk(build_dir):
            gradlefile = None
            if 'build.gradle' in files:
                gradlefile = 'build.gradle'
            elif 'build.gradle.kts' in files:
                gradlefile = 'build.gradle.kts'
            if not gradlefile:
                continue
            path = os.path.join(root, gradlefile)
            with open(path, 'r') as o:
                lines = o.readlines()
            opened = 0
            i = 0
            with open(path, 'w') as o:
                while i < len(lines):
                    line = lines[i]
                    i += 1
                    while line.endswith('\\\n'):
                        line = line.rstrip('\\\n') + lines[i]
                        i += 1
                    if gradle_comment.match(line):
                        o.write(line)
                        continue
                    if opened > 0:
                        opened += line.count('{')
                        opened -= line.count('}')
                        continue
                    if gradle_signing_configs.match(line):
                        opened += 1
                        continue
                    if any(s.match(line) for s in gradle_line_matches):
                        continue
                    if opened == 0:
                        o.write(line)

remove_signing_keys(build_dir)

# Anything that still mentions a signing config outside a comment would break (or silently sign) F-Droid's build.
bad = []
for root, dirs, files in os.walk(build_dir):
    for f in files:
        if f in ('build.gradle', 'build.gradle.kts'):
            path = os.path.join(root, f)
            for n, line in enumerate(open(path), 1):
                code = line.split('//', 1)[0]
                if 'signingConfig' in code:
                    bad.append(f"{os.path.relpath(path, build_dir)}:{n}: {line.strip()}")
if bad:
    print("FAIL: references to a signing config survive F-Droid's strip:")
    print("\n".join("    " + b for b in bad))
    sys.exit(1)
print("    no signing-config references left")
PY

if [[ $STATIC -eq 1 ]]; then
    echo "OK (static check only)"
    exit 0
fi

echo "==> Unsigned release build with no keystore (as on F-Droid's build server)"
cd "$WORK"
env -u PARLEY_KEYSTORE -u PARLEY_KEYSTORE_PASSWORD -u PARLEY_KEY_ALIAS -u PARLEY_KEY_PASSWORD \
    ./gradlew --no-configuration-cache -q "$@" \
    :app:assembleRelease :lists-updater:assembleRelease -x lintVitalRelease

status=0
for apk in app/build/outputs/apk/release/app-release-unsigned.apk \
           lists-updater/build/outputs/apk/release/lists-updater-release-unsigned.apk; do
    if [[ -f "$apk" ]]; then
        echo "    built $apk ($(wc -c < "$apk") bytes)"
    else
        echo "FAIL: missing $apk"; status=1
    fi
done
[[ $status -eq 0 ]] && echo "OK: both apps build unsigned after F-Droid's strip"
exit $status

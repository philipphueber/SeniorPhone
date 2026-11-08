#!/usr/bin/env bash
#
# One-time release-signing setup for a SeniorPhone maintainer.
#
# Creates the release keystore outside the repository, puts its password in the
# system keyring, and writes the gitignored keystore.properties the build reads.
# After that `./gradlew assembleRelease` signs without asking anything.
#
# By default the password is random and never shown: it goes straight into the
# keyring and to keytool (through the environment, so it is not in `ps`). Pass
# --prompt to type your own instead.
#
# The keyring keeps the password out of the repository, shell history and
# transcripts, not out of your own logged-in session:
#     secret-tool lookup service seniorphone key release-keystore
#
# BACK UP the keystore file (and the password, e.g. via the lookup above) somewhere
# off this machine. Lose either and no future version can update installed copies.
#
# Usage:  scripts/setup-signing.sh [--keystore PATH] [--prompt]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${HOME}/keys/seniorphone-release.jks"
ALIAS="seniorphone"
PROMPT=0
# Must match app/build.gradle.kts.
SECRET_SERVICE="seniorphone"
SECRET_KEY="release-keystore"

while [ $# -gt 0 ]; do
    case "$1" in
        --keystore) KEYSTORE="$2"; shift 2 ;;
        --prompt)   PROMPT=1; shift ;;
        -h|--help)  sed -n '3,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }

command -v secret-tool >/dev/null 2>&1 || die "secret-tool is not installed (libsecret)."
[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || die "no D-Bus session bus, so no keyring. Run from your desktop session."

KEYTOOL=""
for k in "${JAVA_HOME:-/nonexistent}/bin/keytool" "$(command -v keytool 2>/dev/null || true)" \
         "${HOME}"/.local/opt/jdk-17*/bin/keytool /usr/lib/jvm/*/bin/keytool; do
    if [ -n "$k" ] && [ -x "$k" ]; then KEYTOOL="$k"; break; fi
done
[ -n "${KEYTOOL}" ] || die "keytool not found. Set JAVA_HOME to a JDK 17."

# Never overwrite: replacing the key breaks updates for everyone who installed the app.
[ -e "${KEYSTORE}" ] && die "${KEYSTORE} already exists. Refusing to replace a signing key."
if secret-tool lookup service "${SECRET_SERVICE}" key "${SECRET_KEY}" >/dev/null 2>&1; then
    die "a keyring entry for ${SECRET_SERVICE}/${SECRET_KEY} already exists. Refusing to overwrite it."
fi

if [ "${PROMPT}" -eq 1 ]; then
    [ -t 0 ] || die "--prompt needs a terminal."
    read -rsp 'Password for the release key (not echoed): ' PW; echo
    read -rsp 'Again: ' PW2; echo
    [ "${PW}" = "${PW2}" ] || die "the two entries did not match."
    unset PW2
    [ "${#PW}" -ge 6 ] || die "at least 6 characters."
else
    PW="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
fi
export SENIORPHONE_SIGNING_PASSWORD="${PW}"

printf '%s' "${PW}" | secret-tool store --label="SeniorPhone release signing key" \
    service "${SECRET_SERVICE}" key "${SECRET_KEY}"
[ "$(secret-tool lookup service "${SECRET_SERVICE}" key "${SECRET_KEY}")" = "${PW}" ] \
    || die "the keyring did not return what was stored."
unset PW
echo "password stored in the keyring (service=${SECRET_SERVICE} key=${SECRET_KEY})"

# Outside the working tree, so no git clean or deleted checkout can take it.
mkdir -p -- "$(dirname -- "${KEYSTORE}")"
chmod 700 -- "$(dirname -- "${KEYSTORE}")"
"${KEYTOOL}" -genkeypair -keystore "${KEYSTORE}" -alias "${ALIAS}" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=SeniorPhone, C=DE" \
    -storepass:env SENIORPHONE_SIGNING_PASSWORD -keypass:env SENIORPHONE_SIGNING_PASSWORD
chmod 600 -- "${KEYSTORE}"
echo "created ${KEYSTORE}"

cat > "${REPO_ROOT}/keystore.properties" <<EOF
# Written by scripts/setup-signing.sh. Gitignored. Holds no password: the build
# reads it from the keyring (secret-tool lookup service ${SECRET_SERVICE} key ${SECRET_KEY}).
storeFile=${KEYSTORE}
keyAlias=${ALIAS}
EOF
chmod 600 -- "${REPO_ROOT}/keystore.properties"
echo "wrote ${REPO_ROOT}/keystore.properties"

"${KEYTOOL}" -list -v -keystore "${KEYSTORE}" -alias "${ALIAS}" \
    -storepass:env SENIORPHONE_SIGNING_PASSWORD | grep -E "Owner:|Valid from:|SHA256:" || true
unset SENIORPHONE_SIGNING_PASSWORD

echo
echo "Done. Back up ${KEYSTORE} and the password off this machine."

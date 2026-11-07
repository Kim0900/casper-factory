#!/usr/bin/env bash
set -euo pipefail
set +x

: "${GEUMBOK_SIGNING_KEY_B64:?Configure the protected signing key GitHub Secret}"
: "${GEUMBOK_SIGNING_PASSWORD:?Configure the protected signing password GitHub Secret}"
: "${GEUMBOK_SIGNING_CERT_SHA256:?Configure the expected signing certificate GitHub Secret}"
: "${RUNNER_TEMP:?CI temporary directory is required}"
: "${ANDROID_HOME:?Android SDK is required}"

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
signing_dir="$(mktemp -d "$RUNNER_TEMP/geumbok-signing.XXXXXX")"
trap 'rm -rf -- "$signing_dir"' EXIT
umask 077
printf '%s' "$GEUMBOK_SIGNING_KEY_B64" | base64 --decode > "$signing_dir/identity.p12"
expected="$(printf '%s' "$GEUMBOK_SIGNING_CERT_SHA256" | tr -d ': \r\n' | tr '[:upper:]' '[:lower:]')"
retired='10731e0ea7f530b66e8a160121fec5c4e0b9c82741c68e09f092162891e5d570'
[[ "$expected" =~ ^[0-9a-f]{64}$ && "$expected" != "$retired" ]] || exit 1

mkdir -p "$root/signed" "$root/verification"
for role in sender receiver; do
    input="$root/$role/build/outputs/apk/debug/$role-debug-unsigned.apk"
    output="$root/signed/geumbok-$role-0.1.2-synthetic.apk"
    if "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$input" >/dev/null 2>&1; then
        echo 'Expected an unsigned CI build.' >&2
        exit 1
    fi
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
        --ks "$signing_dir/identity.p12" --ks-key-alias geumbok \
        --ks-pass env:GEUMBOK_SIGNING_PASSWORD --key-pass env:GEUMBOK_SIGNING_PASSWORD \
        --out "$output" "$input"
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$output" \
        > "$root/verification/$role-signature.txt"
    actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$root/verification/$role-signature.txt")"
    [[ "$actual" == "$expected" && "$actual" != "$retired" ]] || exit 1
    sha256sum "$output" >> "$root/verification/signed-artifacts.sha256"
done

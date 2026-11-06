#!/usr/bin/env bash
# Run once on a trusted computer authenticated to GitHub with repository administration rights.
# No private key or password is printed or retained after the process exits.
set -euo pipefail
set +x
umask 077
repo='Kim0900/casper-factory'
for tool in gh keytool openssl python3 base64; do
    command -v "$tool" >/dev/null || { echo "Required tool: $tool" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo 'Authenticate GitHub CLI on this trusted computer first.' >&2; exit 1; }
existing="$(gh secret list --repo "$repo" --json name --jq '.[].name')"
if printf '%s\n' "$existing" | grep -Eq '^GEUMBOK_SIGNING_(KEY_B64|PASSWORD|CERT_SHA256)$'; then
    echo 'Signing Secrets already exist. Refusing an unplanned identity replacement.' >&2
    exit 1
fi
signing_dir="$(mktemp -d)"
trap 'unset GEUMBOK_NEW_PASSWORD; rm -rf -- "$signing_dir"' EXIT
export GEUMBOK_NEW_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
keytool -genkeypair -alias geumbok -keyalg RSA -keysize 3072 -validity 3650 \
    -dname 'CN=GEUMBOK MIRROR Synthetic CI' -storetype PKCS12 \
    -keystore "$signing_dir/identity.p12" \
    -storepass:env GEUMBOK_NEW_PASSWORD -keypass:env GEUMBOK_NEW_PASSWORD >/dev/null 2>&1
keytool -exportcert -alias geumbok -keystore "$signing_dir/identity.p12" \
    -storepass:env GEUMBOK_NEW_PASSWORD -file "$signing_dir/certificate.der" >/dev/null 2>&1
fingerprint="$(openssl dgst -sha256 -r "$signing_dir/certificate.der" | cut -d' ' -f1)"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]]
base64 < "$signing_dir/identity.p12" | gh secret set GEUMBOK_SIGNING_KEY_B64 --repo "$repo"
printf '%s' "$GEUMBOK_NEW_PASSWORD" | gh secret set GEUMBOK_SIGNING_PASSWORD --repo "$repo"
printf '%s' "$fingerprint" | gh secret set GEUMBOK_SIGNING_CERT_SHA256 --repo "$repo"
echo "Stored three GitHub Secrets. Public signing certificate SHA-256: $fingerprint"
echo 'Private material will now be removed from the temporary directory.'

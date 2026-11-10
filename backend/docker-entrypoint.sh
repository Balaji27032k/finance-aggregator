#!/bin/sh
# Builds the JDBC truststore from the CA certificate passed as an environment variable, then starts the app.
set -eu

: "${AIVEN_CA_PEM:?AIVEN_CA_PEM must contain the Aiven CA certificate (PEM)}"

TLS_DIR=/tmp/tls
mkdir -p "$TLS_DIR"
# Dashboards often flatten the multi-line PEM into one line (or escape newlines), so rebuild a clean PEM from the base64 body.
CA_BODY="$(printf '%s' "$AIVEN_CA_PEM" \
  | sed -e 's/-----BEGIN CERTIFICATE-----//' -e 's/-----END CERTIFICATE-----//' -e 's/\\n//g' \
  | tr -d ' \r\n\t')"
{
  echo '-----BEGIN CERTIFICATE-----'
  printf '%s' "$CA_BODY" | fold -w 64
  echo
  echo '-----END CERTIFICATE-----'
} > "$TLS_DIR/ca.pem"

DB_TRUSTSTORE_PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
export DB_TRUSTSTORE_PASSWORD
export DB_TRUSTSTORE_PATH="$TLS_DIR/ca.p12"
keytool -importcert -noprompt -alias aiven-ca -file "$TLS_DIR/ca.pem" \
  -keystore "$DB_TRUSTSTORE_PATH" -storetype PKCS12 -storepass "$DB_TRUSTSTORE_PASSWORD" >/dev/null

# Hosting platforms give the port to listen on through PORT.
export SERVER_PORT="${PORT:-8080}"

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/app.jar

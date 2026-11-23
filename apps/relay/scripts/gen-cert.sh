#!/usr/bin/env bash
set -euo pipefail

out_dir=${1:-./certs}
extra_ips=${SUPERPI_CERT_IPS:-}
days=${SUPERPI_CERT_DAYS:-3650}

mkdir -p "$out_dir"

openssl req -x509 -newkey rsa:4096 -sha256 -days "$days" -nodes \
  -keyout "$out_dir/ca.key" -out "$out_dir/ca.crt" \
  -subj "/CN=SuperPI Relay CA" >/dev/null 2>&1

openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout "$out_dir/server.key" -out "$out_dir/server.csr" \
  -subj "/CN=superpi-relay" >/dev/null 2>&1

san="IP:127.0.0.1,DNS:localhost"
for ip in $extra_ips; do
  san="$san,IP:$ip"
done

cat >"$out_dir/server.ext" <<EOF
subjectAltName=$san
extendedKeyUsage=serverAuth
EOF

openssl x509 -req -in "$out_dir/server.csr" \
  -CA "$out_dir/ca.crt" -CAkey "$out_dir/ca.key" -CAcreateserial \
  -out "$out_dir/server.crt" -days "$days" -sha256 -extfile "$out_dir/server.ext" >/dev/null 2>&1

openssl x509 -in "$out_dir/server.crt" -noout -text >"$out_dir/server.info"
openssl x509 -in "$out_dir/ca.crt" -noout -fingerprint -sha256 \
  | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f' >"$out_dir/fingerprint.txt"

rm -f "$out_dir"/*.csr "$out_dir"/*.ext "$out_dir"/*.srl

echo "certificates written to $out_dir"
echo "CA fingerprint: $(cat "$out_dir/fingerprint.txt")"
echo "server SAN: $san"

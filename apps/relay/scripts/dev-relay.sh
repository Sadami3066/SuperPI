#!/usr/bin/env bash
set -euo pipefail

port=${SUPERPI_PORT:-8443}
data_dir=${SUPERPI_DATA_DIR:-./data}
cert_dir=${SUPERPI_CERT_DIR:-./certs}

cd "$(dirname "$0")/.."

lan_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit }}')
if [[ -z "${lan_ip}" ]]; then
  lan_ip=$(hostname -I 2>/dev/null | awk '{print $1}')
fi

if [[ ! -f "${cert_dir}/server.crt" || ! -f "${cert_dir}/ca.crt" ]]; then
  SUPERPI_CERT_IPS="${lan_ip}" ./scripts/gen-cert.sh "${cert_dir}"
fi

fingerprint=$(cat "${cert_dir}/fingerprint.txt")
mkdir -p "${data_dir}"

export SUPERPI_HOST=0.0.0.0
export SUPERPI_PORT="${port}"
export SUPERPI_DATA_DIR="${data_dir}"
export SUPERPI_CERT="${cert_dir}/server.crt"
export SUPERPI_KEY="${cert_dir}/server.key"
export SUPERPI_CA="${cert_dir}/ca.crt"

echo
echo "relay address: ${lan_ip}:${port}"
echo "pairing link:  https://${lan_ip}:${port}/#fp=${fingerprint}"
echo "this phone can also use the link with a login secret if it is joining an existing account"
echo

exec node src/server/index.ts

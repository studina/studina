#!/usr/bin/env bash
# Install Docker from its official apt repository on a new Ubuntu 24.04 EC2 VM.
set -euo pipefail
if [[ "${EUID}" -ne 0 ]]; then
    echo "Ejecutá sudo $0 en la VM Ubuntu nueva." >&2
    exit 1
fi
source /etc/os-release
if [[ "${ID:-}" != ubuntu || "${VERSION_ID:-}" != 24.04 || "$(dpkg --print-architecture)" != amd64 ]]; then
    echo 'Este bootstrap está preparado para Ubuntu Server 24.04 LTS amd64.' >&2
    exit 1
fi
for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
        echo "Existe un paquete potencialmente incompatible: $package. Usá una VM nueva o revisá la instalación de Docker antes de continuar." >&2
        exit 1
    fi
done
apt-get update
apt-get install -y ca-certificates curl git python3
install -m 0755 -d /etc/apt/keyrings
curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg \
    --output /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<'SOURCES'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
SOURCES
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker compose version
docker run --rm hello-world
echo 'Docker listo. Los comandos de operación usan sudo; no se modificaron grupos, firewall ni permisos del socket.'

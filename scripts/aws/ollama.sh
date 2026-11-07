#!/usr/bin/env bash
# Explicit preparation only: this never recreates the API or enables its provider.
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ "$#" -ne 1 || ! "$1" =~ ^(prepare|status)$ ]]; then
    echo "Uso: $0 prepare|status" >&2
    echo 'prepare descarga llama3.2:3b y gemma3:1b; no habilita Ollama en la API.' >&2
    exit 1
fi

preflight --ollama
if [[ "$1" == "status" ]]; then
    compose --ollama ps --all ollama
    container_id="$(compose --ollama ps --all --quiet ollama)"
    if [[ -z "$container_id" ]]; then
        echo 'Ollama todavía no tiene un contenedor preparado en este stack.'
        exit
    fi
    # Selected diagnostics only: inspect must never print credentials or env.
    docker inspect --format 'Ollama: status={{.State.Status}} OOMKilled={{.State.OOMKilled}} exitCode={{.State.ExitCode}} {{if .State.Health}}health={{.State.Health.Status}}{{end}}' "$container_id"
    compose --ollama exec -T ollama ollama list
    exit
fi

# The pinned image is ~5.2 GiB unpacked, plus ~2.8 GB for both models and download layers.
# Leave room for Docker extraction and the existing application on the 30 GiB VM.
# Check Docker's filesystem rather than the current repository's mount.
docker_root="$(docker info --format '{{.DockerRootDir}}')"
available_kib="$(df -Pk -- "$docker_root" | awk 'NR == 2 {print $4}')"
if [[ ! "$available_kib" =~ ^[0-9]+$ || "$available_kib" -lt 14680064 ]]; then
    echo 'Ollama necesita al menos 14 GiB libres en el disco de Docker antes de preparar los modelos.' >&2
    exit 1
fi

compose --ollama pull ollama
compose --ollama up --detach --no-deps --wait --wait-timeout 120 ollama
# Store both models on disk; the server still loads only one at a time.
for model in llama3.2:3b gemma3:1b; do
    compose --ollama exec -T ollama ollama pull "$model"
    compose --ollama exec -T ollama ollama show "$model" >/dev/null
done
echo 'Modelos llama3.2:3b y gemma3:1b preparados en el volumen persistente. La API sigue con su configuración actual.'

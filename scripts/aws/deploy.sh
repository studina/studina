#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
mode=()
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --ollama|--model-armor) mode+=("$1"); shift ;;
        *) echo "Uso: $0 [--ollama] [--model-armor] (configuración en deploy/aws/.env)" >&2; exit 1 ;;
    esac
done
preflight "${mode[@]}"
compose "${mode[@]}" pull --ignore-buildable
# Avoid compiling the Java and frontend applications at the same time on the VM.
compose "${mode[@]}" --parallel 1 build
compose "${mode[@]}" up --detach --wait --wait-timeout 300 postgres pgvector directus
# Run the additive schema migration on every release and retain its success status
# for dina-api's service_completed_successfully dependency.
compose "${mode[@]}" stop dina-api
compose "${mode[@]}" up --no-deps --force-recreate --exit-code-from directus-schema directus-schema
# A project-wide `up` would start the completed schema job a second time.
# Dependencies were validated above; start each application layer explicitly.
compose "${mode[@]}" up --detach --wait --wait-timeout 300 --no-deps dina-api
compose "${mode[@]}" up --detach --wait --wait-timeout 300 --no-deps frontend
compose "${mode[@]}" ps --all
printf 'Studina: https://%s\n' "$(python3 "$AWS_SCRIPT_DIR/config.py" get STUDINA_DOMAIN)"
echo 'Verificá HTTPS y el flujo de registro, IA y documentos con la guía de despliegue.'

#!/usr/bin/env bash
# Shared by the operational commands; do not source the secret .env as shell code.
set -euo pipefail

AWS_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
STUDINA_ROOT="$(cd -- "$AWS_SCRIPT_DIR/../.." && pwd)"
STUDINA_ENV="$STUDINA_ROOT/deploy/aws/.env"

require_config() {
    command -v python3 >/dev/null || { echo 'Falta python3.' >&2; return 1; }
    python3 "$AWS_SCRIPT_DIR/config.py" check "$@"
}

compose() (
    local compose_files=(--file "$STUDINA_ROOT/compose.aws.yaml")
    # Only explicit CLI flags opt into additional services; never host profiles.
    local with_ollama=false with_model_armor=false
    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --ollama) with_ollama=true; shift ;;
            --model-armor) with_model_armor=true; shift ;;
            *) break ;;
        esac
    done
    if [[ "$with_ollama" == true ]]; then
        compose_files+=(--file "$STUDINA_ROOT/compose.aws.ollama.yaml")
    fi
    if [[ "$with_model_armor" == true ]]; then
        compose_files+=(--file "$STUDINA_ROOT/compose.aws.model-armor.yaml")
    fi
    # Host shell variables must not silently override the reviewed secret file.
    local managed_key
    while IFS= read -r managed_key; do
        unset "$managed_key"
    done < <(python3 "$AWS_SCRIPT_DIR/config.py" keys)
    unset COMPOSE_PROFILES COMPOSE_FILE COMPOSE_PROJECT_NAME
    # These generation and model overrides are optional in the secret file.
    # Preserve them there when configured, but never inherit them from a dev shell.
    unset AI_GENERATION_MODEL AI_CLASSIFICATION_MODEL AI_SUMMARY_MODEL AI_GENERATION_TIMEOUT_MS
    unset GEMINI_STUDY_PLAN_MODEL GEMINI_MODEL
    unset OLLAMA_BASE_URL OLLAMA_READ_TIMEOUT_SECONDS OLLAMA_QUEUE_TIMEOUT_SECONDS
    unset OLLAMA_MAX_QUEUED_REQUESTS OLLAMA_NUM_THREADS STUDINA_API_TIMEOUT
    docker compose --project-name studina-aws --env-file "$STUDINA_ENV" \
        "${compose_files[@]}" "$@"
)

preflight() {
    local config_args=() mode
    for mode in "$@"; do
        if [[ "$mode" == "--model-armor" ]]; then
            config_args=(--model-armor)
        fi
    done
    require_config "${config_args[@]}"
    command -v docker >/dev/null || { echo 'Falta Docker. Revisá bootstrap-ubuntu.sh.' >&2; return 1; }
    docker compose version >/dev/null
    docker info >/dev/null || { echo 'No se puede acceder a Docker. En EC2 ejecutá este script con sudo.' >&2; return 1; }
    compose "$@" config --quiet
}

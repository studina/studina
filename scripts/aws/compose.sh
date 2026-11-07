#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
mode=()
if [[ "${1:-}" == "--ollama" ]]; then
    mode=(--ollama)
    shift
fi
preflight "${mode[@]}"
compose "${mode[@]}" "$@"

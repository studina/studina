#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
mode=()
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --ollama|--model-armor) mode+=("$1"); shift ;;
        *) break ;;
    esac
done
preflight "${mode[@]}"
compose "${mode[@]}" "$@"

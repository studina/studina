#!/usr/bin/env bash
set -euo pipefail
AWS_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$#" -ne 2 ]]; then
    echo "Uso: $0 DOMINIO_PUBLICO EMAIL_ADMINISTRADOR" >&2
    exit 1
fi
exec python3 "$AWS_SCRIPT_DIR/config.py" setup "$1" "$2"

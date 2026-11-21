#!/usr/bin/env bash
# Consistent application backup: stop writers while dumping DBs and archiving files.
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
if [[ "$#" -gt 1 ]]; then
    echo "Uso: $0 [DIRECTORIO_BACKUPS]" >&2
    exit 1
fi
preflight
umask 077
backup_parent="${1:-$STUDINA_ROOT/backups}"
mkdir -p -- "$backup_parent"
backup_parent="$(cd -- "$backup_parent" && pwd)"
backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_staging="$(mktemp -d "$backup_parent/$backup_stamp.partial.XXXXXX")"
backup_final="$backup_parent/$backup_stamp"
if [[ -e "$backup_final" ]]; then
    echo 'Ya existe un backup con esta fecha; reintentá en un segundo.' >&2
    exit 1
fi
# Download before stopping the application; the archive container has no network.
docker image inspect alpine:3.20 >/dev/null 2>&1 || docker pull alpine:3.20
directus_container="$(compose ps --all --quiet directus)"
frontend_container="$(compose ps --all --quiet frontend)"
api_container="$(compose ps --all --quiet dina-api)"
if [[ -z "$directus_container" || -z "$frontend_container" || -z "$api_container" ]]; then
    echo 'Primero desplegá la aplicación; faltan los contenedores de Directus, API o frontend.' >&2
    exit 1
fi
# Restore metadata only: select the Compose file label, never inspect env/secrets.
docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' \
    "$api_container" > "$backup_staging/compose-files.txt"
restart_required=false
start_existing() {
    local container_id="$1" state deadline=$((SECONDS + 300))
    docker start "$container_id" >/dev/null || return 1
    while (( SECONDS < deadline )); do
        state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container_id")" || return 1
        case "$state" in
            'running healthy') return 0 ;;
            'running starting') sleep 1 ;;
            *) echo "El contenedor $container_id no está saludable: $state" >&2; return 1 ;;
        esac
    done
    echo "El contenedor $container_id no quedó saludable en 300 segundos." >&2
    return 1
}
resume_app() {
    if [[ "$restart_required" == true ]]; then
        echo 'Reanudando Studina...'
        # Exact IDs preserve image/configuration, including optional Ollama.
        # Compose start would also restart the completed schema dependency.
        local container_id
        for container_id in "$directus_container" "$api_container" "$frontend_container"; do
            start_existing "$container_id" || {
                echo 'No se pudo reanudar Studina; revisá scripts/aws/compose.sh ps --all y logs.' >&2
                return 1
            }
        done
    fi
}
trap resume_app EXIT
restart_required=true
compose stop frontend dina-api directus
compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "$backup_staging/directus.dump"
compose exec -T pgvector sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "$backup_staging/rag.dump"
docker run --rm --network none --volumes-from "$directus_container:ro" \
    --mount "type=bind,src=$backup_staging,dst=/backup" alpine:3.20 \
    tar -czf /backup/directus-files.tar.gz -C /directus uploads extensions
docker run --rm --network none --volumes-from "$frontend_container:ro" \
    --mount "type=bind,src=$backup_staging,dst=/backup" alpine:3.20 \
    tar -czf /backup/caddy.tar.gz -C / data config
cp -- "$STUDINA_ENV" "$backup_staging/.env"
cp -- "$STUDINA_ROOT/compose.aws.yaml" "$backup_staging/compose.aws.yaml"
cp -- "$STUDINA_ROOT/compose.aws.ollama.yaml" "$backup_staging/compose.aws.ollama.yaml"
cp -- "$STUDINA_ROOT/compose.aws.model-armor.yaml" "$backup_staging/compose.aws.model-armor.yaml"
cp -- "$STUDINA_ROOT/deploy/aws/Caddyfile" "$backup_staging/Caddyfile"
git -C "$STUDINA_ROOT" rev-parse HEAD > "$backup_staging/commit.txt"
(
    cd -- "$backup_staging"
    sha256sum .env *.dump *.tar.gz compose.aws.yaml compose.aws.ollama.yaml compose.aws.model-armor.yaml compose-files.txt Caddyfile commit.txt > SHA256SUMS
)
resume_app
restart_required=false
trap - EXIT
mv -- "$backup_staging" "$backup_final"
printf 'Backup completo: %s\n' "$backup_final"
echo 'Incluye secretos. Copialo fuera de la VM con acceso restringido; verificá SHA256SUMS y ensayá la restauración.'

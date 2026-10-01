#!/bin/sh
set -eu

apk add --no-cache curl jq postgresql-client >/dev/null

directus_url="${DIRECTUS_URL:-http://directus:8055}"
login_response="$(curl --silent --show-error --fail \
  -X POST "$directus_url/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" \
    '{email: $email, password: $password}')")"
token="$(printf '%s' "$login_response" | jq -er '.data.access_token')"

api() {
  curl --silent --show-error --fail \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' "$@"
}

collection_exists() {
  api "$directus_url/collections/$1" >/dev/null 2>&1
}

field_exists() {
  api "$directus_url/fields/$1/$2" >/dev/null 2>&1
}

relation_exists() {
  api "$directus_url/relations/$1/$2" >/dev/null 2>&1
}

ensure_collection() {
  collection="$1"
  if ! collection_exists "$collection"; then
    api -X POST "$directus_url/collections" \
      -d "$(jq -n --arg collection "$collection" \
          '{collection: $collection, schema: {}, meta: {singleton: false}, fields: [
            {field: "id", type: "uuid",
             meta: {hidden: true, readonly: true, interface: "input", special: ["uuid"]},
             schema: {is_primary_key: true, length: 36, has_auto_increment: false}}
          ]}')" >/dev/null
    echo "Created UUID collection $collection"
  else
    id_type="$(api "$directus_url/fields/$collection/id" | jq -r '.data.type // empty')"
    if [ "$id_type" != "uuid" ]; then
      echo "Collection $collection uses primary key type '$id_type'; run make clean to recreate the schema with UUID IDs." >&2
      exit 1
    fi
  fi
}

ensure_field() {
  collection="$1"
  field="$2"
  type="$3"
  data_type="$4"
  required="$5"
  max_length="${6:-}"

  if field_exists "$collection" "$field"; then
    existing_type="$(api "$directus_url/fields/$collection/$field" | jq -r '.data.type // empty')"
    if [ "$existing_type" != "$type" ]; then
      echo "Field $collection.$field has type '$existing_type', expected '$type'; run make clean to recreate the schema." >&2
      exit 1
    fi
    expected_nullable="$([ "$required" = true ] && echo false || echo true)"
    existing_nullable="$(api "$directus_url/fields/$collection/$field" | jq -r '.data.schema.is_nullable // true')"
    if [ "$existing_nullable" != "$expected_nullable" ]; then
      api -X PATCH "$directus_url/fields/$collection/$field" \
        -d "$(jq -n --argjson nullable "$expected_nullable" '{schema: {is_nullable: $nullable}}')" >/dev/null
    fi
    return
  fi

  schema="$(jq -n \
    --arg data_type "$data_type" \
    --argjson nullable "$([ "$required" = true ] && echo false || echo true)" \
    --arg max_length "$max_length" \
    '({data_type: $data_type, is_nullable: $nullable}
      + (if $max_length == "" then {} else {max_length: ($max_length | tonumber)} end))')"

  api -X POST "$directus_url/fields/$collection" \
    -d "$(jq -n \
      --arg field "$field" \
      --arg type "$type" \
      --argjson schema "$schema" \
      --argjson required "$required" \
      '{field: $field, type: $type, schema: $schema,
        meta: {interface: "input", required: $required}}')" >/dev/null
  echo "Created field $collection.$field"
}

ensure_relation() {
  collection="$1"
  field="$2"
  related_collection="$3"
  related_field="${4:-id}"
  # CASCADE por defecto: todas estas claves foráneas apuntan a columnas NOT NULL,
  # así que un SET NULL no puede cumplirse y la base rechaza el borrado del padre
  # con "null value in column ... violates not-null constraint". Las relaciones que
  # deben frenar el borrado pasan RESTRICT explícito.
  on_delete="${5:-CASCADE}"

  if relation_exists "$collection" "$field"; then
    # El migrador es aditivo, pero la regla de borrado sí se corrige: si no, una
    # instalación existente se queda con el SET NULL viejo y sigue rota.
    current_on_delete="$(api "$directus_url/relations/$collection/$field" \
      | jq -r '.data.schema.on_delete // empty')"
    if [ "$current_on_delete" != "$on_delete" ]; then
      api -X PATCH "$directus_url/relations/$collection/$field" \
        -d "$(jq -n --arg on_delete "$on_delete" '{schema: {on_delete: $on_delete}}')" >/dev/null
      echo "Updated relation $collection.$field on_delete: $current_on_delete -> $on_delete"
    fi
    return
  fi

  api -X POST "$directus_url/relations" \
    -d "$(jq -n \
      --arg collection "$collection" \
      --arg field "$field" \
      --arg related_collection "$related_collection" \
      --arg related_field "$related_field" \
      --arg on_delete "$on_delete" \
      '{collection: $collection, field: $field,
        related_collection: $related_collection,
        schema: {foreign_key_table: $related_collection,
          foreign_key_column: $related_field, on_delete: $on_delete}}')" >/dev/null
  echo "Created relation $collection.$field -> $related_collection.$related_field"
}

ensure_learning_collection() {
  collection="$1"
  shift
  ensure_collection "$collection"
  while [ "$#" -gt 0 ]; do
    if [ "$#" -lt 5 ]; then
      ensure_field "$collection" "$1" "$2" "$3" "$4" ""
      break
    fi
    ensure_field "$collection" "$1" "$2" "$3" "$4" "$5"
    shift 5
  done
}

ensure_learning_collection topics \
  subject_id uuid uuid true "" \
  name string 'character varying' true 160 \
  normalized_name string 'character varying' true 160 \
  description text text false ""

# Application collections. Directus creates directus_users, directus_files and
# directus_folders itself; these are only referenced by the relations below.
ensure_learning_collection subjects \
  name string 'character varying' false 255 \
  user uuid uuid false ""

# `tags` es nativo de Directus. `subject` es la extensión que usa la API para
# asociar cada archivo a su materia, además de la jerarquía de carpetas.
ensure_field directus_files subject uuid uuid false ""

ensure_learning_collection flashcards \
  subject uuid uuid false "" \
  title string 'character varying' false 255 \
  instructions string 'character varying' false 255 \
  difficulty string 'character varying' false 255 \
  source_file_ids json json false "" \
  date_created timestamp timestamp true ""

ensure_learning_collection quizzes \
  subject uuid uuid false "" \
  title string 'character varying' false 255 \
  instructions text text false "" \
  difficulty string 'character varying' false 255 \
  source_file_ids json json false "" \
  date_created timestamp timestamp true ""

ensure_learning_collection quiz_questions \
  quiz_id uuid uuid true "" \
  question text text true "" \
  options json json true "" \
  correct_option_id string 'character varying' true 64 \
  explanation text text false "" \
  topic_id uuid uuid true "" \
  position integer integer true ""

ensure_learning_collection file_topics \
  file_id uuid uuid true "" \
  topic_id uuid uuid true ""

ensure_learning_collection flashcard_cards \
  flashcard_set_id uuid uuid true "" \
  term text text true "" \
  definition text text true "" \
  topic_id uuid uuid true "" \
  position integer integer true ""

ensure_learning_collection user_topic_progress \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  topic_id uuid uuid true "" \
  mastery_score float real true "" \
  unique_resources_seen integer integer true "" \
  total_attempts integer integer true "" \
  last_seen_at timestamp timestamp false ""

ensure_learning_collection flashcard_attempts \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  flashcard_set_id uuid uuid true "" \
  card_id uuid uuid true "" \
  attempt_id uuid uuid true "" \
  rating integer integer true "" \
  created_at string 'character varying' true 40

ensure_learning_collection quiz_attempts \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  quiz_id uuid uuid true "" \
  question_id uuid uuid true "" \
  attempt_id uuid uuid true "" \
  selected_option_id string 'character varying' true 64 \
  is_correct boolean boolean true "" \
  created_at string 'character varying' true 40

# Chat con Dina. Los tipos replican los del schema.yaml que venía de main, para no
# cambiarle el modelo a nadie al unificar los dos mecanismos de migración.
ensure_learning_collection chat_conversations \
  user uuid uuid false "" \
  subject uuid uuid false "" \
  title string 'character varying' false 255 \
  status string 'character varying' false 255 \
  date_created date date false "" \
  date_updated dateTime 'timestamp without time zone' false "" \
  summary text text false "" \
  summary_until dateTime 'timestamp without time zone' false ""

ensure_learning_collection chat_messages \
  conversation uuid uuid false "" \
  role string 'character varying' false 255 \
  content text text false "" \
  source_file_ids json json false "" \
  provider string 'character varying' false 255 \
  model string 'character varying' false 255 \
  date_created dateTime 'timestamp without time zone' false ""

ensure_learning_collection user_resource_progress \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  resource_type string 'character varying' true 16 \
  resource_id uuid uuid true "" \
  mastery_score float real true "" \
  times_seen integer integer true "" \
  last_seen_at timestamp timestamp true ""

# Foreign keys. The referenced Directus native collections exist after the
# Directus database initialization and are safe to reference here.
ensure_relation subjects user directus_users id
ensure_relation directus_files subject subjects id 'SET NULL'
ensure_relation flashcards subject subjects id
ensure_relation quizzes subject subjects id
ensure_relation topics subject_id subjects id
ensure_relation file_topics file_id directus_files id
ensure_relation quiz_questions quiz_id quizzes id
ensure_relation quiz_questions topic_id topics id RESTRICT
ensure_relation flashcard_cards flashcard_set_id flashcards id
ensure_relation flashcard_cards topic_id topics id RESTRICT
ensure_relation flashcard_attempts user_id directus_users id
ensure_relation flashcard_attempts subject_id subjects id
ensure_relation flashcard_attempts flashcard_set_id flashcards id
ensure_relation flashcard_attempts card_id flashcard_cards id
ensure_relation user_resource_progress user_id directus_users id
ensure_relation user_resource_progress subject_id subjects id
ensure_relation user_topic_progress subject_id subjects id
ensure_relation user_topic_progress topic_id topics id
ensure_relation user_topic_progress user_id directus_users id
ensure_relation quiz_attempts user_id directus_users id
ensure_relation quiz_attempts subject_id subjects id
ensure_relation quiz_attempts quiz_id quizzes id
ensure_relation quiz_attempts question_id quiz_questions id
ensure_relation chat_conversations user directus_users id 'SET NULL'
ensure_relation chat_conversations subject subjects id 'SET NULL'
ensure_relation chat_messages conversation chat_conversations id 'SET NULL'

if [ "${DB_CLIENT:-}" != "pg" ]; then
  echo "Expected PostgreSQL (DB_CLIENT=pg) to create compound uniqueness constraints; got '${DB_CLIENT:-unset}'." >&2
  exit 1
fi

PGHOST="${DB_HOST:-postgres}" \
PGPORT="${DB_PORT:-5432}" \
PGDATABASE="$DB_DATABASE" \
PGUSER="$DB_USER" \
PGPASSWORD="$DB_PASSWORD" \
psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE UNIQUE INDEX IF NOT EXISTS file_topics_file_topic_uq
  ON file_topics (file_id, topic_id);
CREATE UNIQUE INDEX IF NOT EXISTS user_resource_progress_user_resource_uq
  ON user_resource_progress (user_id, resource_type, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS user_topic_progress_user_topic_uq
  ON user_topic_progress (user_id, topic_id);
CREATE UNIQUE INDEX IF NOT EXISTS flashcard_attempts_user_attempt_uq
  ON flashcard_attempts (user_id, attempt_id);
CREATE UNIQUE INDEX IF NOT EXISTS quiz_attempts_user_attempt_uq
  ON quiz_attempts (user_id, attempt_id);
SQL

echo 'Directus learning schema is ready.'

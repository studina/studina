#!/bin/sh
set -eu

apk add --no-cache curl jq >/dev/null

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

  if relation_exists "$collection" "$field"; then
    return
  fi

  api -X POST "$directus_url/relations" \
    -d "$(jq -n \
      --arg collection "$collection" \
      --arg field "$field" \
      --arg related_collection "$related_collection" \
      --arg related_field "$related_field" \
      '{collection: $collection, field: $field,
        related_collection: $related_collection,
        schema: {foreign_key_table: $related_collection,
          foreign_key_column: $related_field, on_delete: "SET NULL"}}')" >/dev/null
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

ensure_learning_collection flashcards \
  subject uuid uuid false "" \
  title string 'character varying' false 255 \
  cards json json false "" \
  instructions string 'character varying' false 255 \
  difficulty string 'character varying' false 255 \
  source_file_ids json json false "" \
  date_created timestamp timestamp true ""

ensure_learning_collection quizzes \
  subject uuid uuid false "" \
  title string 'character varying' false 255 \
  questions json json false "" \
  instructions text text false "" \
  difficulty string 'character varying' false 255 \
  source_file_ids json json false ""

ensure_learning_collection quiz_question_topics \
  quiz_id uuid uuid true "" \
  question_id uuid uuid true "" \
  topic_id uuid uuid true ""

ensure_learning_collection file_topics \
  file_id uuid uuid true "" \
  topic_id uuid uuid true ""

ensure_learning_collection flashcard_cards \
  flashcard_set_id uuid uuid true "" \
  card_id uuid uuid true "" \
  term text text true "" \
  definition text text true "" \
  topic_ids json json false "" \
  primary_topic_id uuid uuid false "" \
  position integer integer true ""

ensure_learning_collection flashcard_card_topics \
  flashcard_set_id uuid uuid true "" \
  card_id uuid uuid true "" \
  topic_id uuid uuid true ""

ensure_learning_collection study_sessions \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  flashcard_set_id uuid uuid false "" \
  quiz_id uuid uuid false "" \
  status string 'character varying' true 24 \
  started_at string 'character varying' true 40 \
  completed_at string 'character varying' false 40

ensure_learning_collection user_topic_progress \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  topic_id uuid uuid true "" \
  mastery_score float real true "" \
  coverage float real true "" \
  unique_resources_seen integer integer true "" \
  total_resources integer integer true "" \
  total_attempts integer integer true "" \
  last_seen_at string 'character varying' false 40

ensure_learning_collection flashcard_attempts \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  flashcard_set_id uuid uuid true "" \
  card_id uuid uuid true "" \
  session_id uuid uuid false "" \
  idempotency_key uuid uuid false "" \
  rating integer integer true "" \
  created_at string 'character varying' true 40

api -X PATCH "$directus_url/fields/flashcard_attempts/idempotency_key" \
  -d '{"schema":{"is_unique":true}}' >/dev/null

ensure_learning_collection quiz_attempts \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  quiz_id uuid uuid true "" \
  question_id uuid uuid true "" \
  session_id uuid uuid false "" \
  idempotency_key uuid uuid true "" \
  selected_option_id string 'character varying' true 64 \
  is_correct boolean boolean true "" \
  rating integer integer true "" \
  created_at string 'character varying' true 40

api -X PATCH "$directus_url/fields/quiz_attempts/idempotency_key" \
  -d '{"schema":{"is_unique":true}}' >/dev/null

ensure_learning_collection learning_evidence \
  user_id uuid uuid true "" \
  subject_id uuid uuid true "" \
  topic_id uuid uuid true "" \
  session_id uuid uuid false "" \
  idempotency_key uuid uuid false "" \
  resource_type string 'character varying' true 32 \
  resource_id uuid uuid true "" \
  rating integer integer true "" \
  created_at string 'character varying' true 40

ensure_learning_collection user_flashcard_progress \
  user_id uuid uuid true "" \
  card_id uuid uuid true "" \
  mastery_score float real true "" \
  times_seen integer integer true "" \
  last_rating integer integer true "" \
  last_seen_at string 'character varying' true 40

# Foreign keys. The referenced Directus native collections exist after the
# Directus database initialization and are safe to reference here.
ensure_relation subjects user directus_users id
ensure_relation flashcards subject subjects id
ensure_relation quizzes subject subjects id
ensure_relation quiz_question_topics quiz_id quizzes id
ensure_relation quiz_question_topics topic_id topics id
ensure_relation topics subject_id subjects id
ensure_relation flashcard_cards flashcard_set_id flashcards id
ensure_relation flashcard_cards primary_topic_id topics id
ensure_relation flashcard_card_topics flashcard_set_id flashcards id
ensure_relation flashcard_card_topics topic_id topics id
ensure_relation flashcard_attempts subject_id subjects id
ensure_relation flashcard_attempts session_id study_sessions id
ensure_relation learning_evidence session_id study_sessions id
ensure_relation study_sessions subject_id subjects id
ensure_relation study_sessions flashcard_set_id flashcards id
ensure_relation study_sessions quiz_id quizzes id
ensure_relation study_sessions user_id directus_users id
ensure_relation user_topic_progress subject_id subjects id
ensure_relation user_topic_progress topic_id topics id
ensure_relation user_topic_progress user_id directus_users id
ensure_relation quiz_attempts user_id directus_users id
ensure_relation quiz_attempts subject_id subjects id
ensure_relation quiz_attempts quiz_id quizzes id
ensure_relation quiz_attempts session_id study_sessions id

echo 'Directus learning schema is ready.'
# Progreso de aprendizaje en Studina

> **Estado actual:** Flashcards y cuestionarios aportan evidencia al progreso compartido por topic.

## 1. Idea general

Queremos guardar evidencia del progreso del estudiante. La idea es que los diferentes recursos (flashcards, quizzes, etc.) contribuyan al mismo conocimiento del usuario sobre un tema.

Flujo conceptual:

```text
Materia
  └── Topics canónicos
        ├── Archivos asociados
        └── Flashcards asociadas
              └── Intentos de un usuario
                    ├── Histórico inmutable
                    ├── Dominio por tarjeta
                    └── Evidencia para el topic
```

La separación importante es:

- **Attempt / intento:** una respuesta concreta en un momento concreto. Se conserva como histórico.
- **Mastery / dominio:** estimación actual de qué tan bien parece conocer el contenido.
- **Coverage / cobertura:** qué proporción de las tarjetas asociadas a un tema ya practicó.

Mastery estima el desempeño y coverage describe cuánto contenido se recorrió.

No son métricas intercambiables. Una persona puede tener mastery alto con poca evidencia, o mastery moderado sustentado por mucha evidencia.

---

## Cómo se definen los topics

La lógica está al momento de subir un nuevo archivo en una materia. En `FileController.uploadFile` ocurre lo siguiente:

1. Se chequea el token del usuario y que la materia le pertenezca.
2. Se sube el archivo a Directus y lo asocia a la materia.
3. Para PDF o DOCX extrae texto y lo indexa en pgvector.
4. Si el indexado termina correctamente, invoca `TopicCatalogService.detectAndAssociate`.
5. El clasificador recibe la lista de topics de esa materia (si existe) y hasta 12.000 caracteres del texto extraído. Ahí, se le pide a la IA que nos diga qué IDs del catálogo ya existente representan los temas del documento y si hay conceptos centrales que no están cubiertos por el catálogo, que cree los topics nuevos que le parezcan.

El formato esperado es:

```json
{
    "existing_topic_ids": ["12"],
    "new_topics": [
    {
        "name": "Elección de líder",
        "description": "Mecanismos para seleccionar un nodo coordinador en un sistema distribuido."
    }
    ]
}
```

Por ejemplo, si el catálogo ya contiene `Consistencia eventual/fuerte` y el documento habla de consistencia eventual, la IA debería devolver el ID de ese topic existente. Si el mismo documento también introduce un concepto central no cubierto, como `Elección de líder`, puede devolver además esa propuesta en `new_topics`.

6. El backend resuelve la clasificación de forma segura:

   1. Comprueba que cada ID de `existing_topic_ids` exista en el catálogo de esa materia. Rechaza IDs desconocidos o de otra materia; no acepta que la IA invente IDs.
   2. Conserva los topics existentes seleccionados, sin duplicarlos si la IA repite un ID.
   3. Valida las propuestas nuevas: deben tener nombre y no pueden ser más de 3 por documento.
   4. Crea cada topic nuevo en `topics`. Como protección secundaria, `createOrReuse` compara su nombre normalizado con el catálogo; una coincidencia exacta reutiliza el topic en vez de duplicarlo.
   5. Combina los topics existentes y los recién creados, elimina IDs repetidos y asocia cada resultado al archivo mediante `file_topics`.

   En resumen, el clasificador decide **qué conceptos ya están representados** y **cuáles faltan**; el backend valida esa decisión y persiste las asociaciones.

7. Al finalizar, queda una relación `file_topics` por cada topic existente seleccionado o nuevo creado. Si la IA no devuelve ningún topic existente ni ninguna propuesta nueva válida, la clasificación se considera fallida y se registra la advertencia correspondiente.

Si la clasificación falla, se registra una advertencia en el log y la carga del archivo puede seguir respondiendo como exitosa, incluyendo `topicDetectionWarning`. Por eso un archivo indexado no garantiza que se hayan asociado temas: hay que revisar esa advertencia/log.

### Temas disponibles para generar tarjetas

Al generar flashcards, el backend consulta las asociaciones `file_topics` de los archivos seleccionados y le pasa al generador únicamente la unión de esos topics. Si algunos archivos seleccionados no tienen topics, se ignoran; no se amplía el catálogo por esa razón. Sólo cuando ninguno de los archivos seleccionados tiene topics asociados se usa como fallback el catálogo completo de la materia y se registra un warning.

La generación de cuestionarios usa la misma selección. Cada pregunta recibe un UUID y uno o más `topic_ids` del conjunto permitido, y esas relaciones quedan persistidas en `quiz_question_topics`.

---

## Generación y persistencia de flashcards

Al momento de generar, le pegamos a este endpoint y ocurre lo siguiente:

```text
POST /api/ai/files/flashcards
  → RagController.generateFlashcards
  → autorización de usuario, materia y archivos
  → topics asociados a los archivos seleccionados (fallback al catálogo de materia sólo si ninguno tiene topics)
  → RagQueryService.generateFlashcards (*)
  → FlashcardSetService.create
```

(*)`RagQueryService`:

- Recupera fragmentos relevantes de pgvector para los archivos seleccionados, le pasa a la IA los topics disponibles y la IA te escupe algo como esto:


```text
{
  "cards": [
    {
      "term": "¿Qué diferencia hay entre consistencia eventual y consistencia fuerte?",
      "definition": "En la consistencia fuerte, una lectura refleja la última escritura confirmada. En la eventual, las réplicas pueden devolver temporalmente valores distintos, pero convergen si no reciben nuevas actualizaciones.",
      "topic_ids": ["12"]
    },
    {
      "term": "¿Cómo se elige un líder en un sistema distribuido?",
      "definition": "Los nodos ejecutan un protocolo de elección para designar uno como coordinador. El mecanismo debe permitir elegir otro si el líder falla.",
      "topic_ids": ["18", "23"]
    }
  ]
}
```

Nosotros agarramos cada una, le metemos un ID con UUID, y le seteamos `primaryTopicId` y como valor agarramos el primer elemento de `topic_ids`.


---

## Flujo de estudio: flashcards


1. La persona abre un set o genera uno.
2. La página muestra la tarjeta y permite revelar la respuesta.
3. Cuando la respuesta está revelada, le muestra 5 opciones:
   - `0`: No lo sabía.
   - `1`: Me sonaba, pero no pude recordarlo.
   - `2`: Lo recordé con bastante dificultad.
   - `3`: Lo sabía, pero dudé.
   - `4`: Lo sabía con seguridad.
4. Al elegir una opción, envía el rating a la API.
5. Si la API responde correctamente, el frontend pasa a la tarjeta siguiente.
6. Mientras se envía la evaluación, se deshabilitan los cinco botones para evitar envíos dobles desde esos controles.

### Navegación y sesión

Al abrir o generar un set, la API crea una sesión asociada al usuario, materia y set. En la primera pasada, **Siguiente** permanece deshabilitado hasta guardar el rating de la tarjeta actual; la UI también bloquea la navegación mientras espera la respuesta. Al calificar la última tarjeta, completa la sesión.

El modo “Repaso ponderado” crea otra sesión que el usuario puede finalizar con “Terminar repaso”. Si sale antes de finalizar, la sesión se marca como abandonada. Cada intento incluye el `sessionId` y un UUID de idempotencia.

---

## 5. Endpoint para registrar un intento

La ruta implementada es:

```text
POST /flashcards/{flashcardSetId}/attempts?subjectId={subjectId}
```

El body que manda el frontend es conceptualmente:

```json
{
  "cardId": "UUID-de-la-tarjeta",
  "rating": 3,
  "sessionId": "UUID-de-la-sesión",
  "idempotencyKey": "UUID-del-intento"
}
```

El endpoint de `FlashcardController`:

1. Resuelve el usuario desde el bearer token.
2. Verifica ownership de la materia.
3. Busca el set y comprueba que pertenece a la materia indicada.
4. Busca dentro del JSON `flashcards.cards` una tarjeta cuyo ID coincide. Si no pertenece a ese set, rechaza el request.
5. Llama a `FlashcardProgressService.recordAttempt`. El servicio valida que el rating exista y esté en rango `0..4`. Después agrega una fila nueva en `flashcard_attempts`, donde guarda:


- `user_id`
- `subject_id`
- `flashcard_set_id`
- `card_id`
- `session_id` (si la respuesta ocurre en una sesión)
- `idempotency_key` (UUID generado por el frontend para reintentos seguros)
- `rating`
- `created_at`

Después reconstruye el agregado de `user_flashcard_progress` desde el histórico inmutable de intentos, evitando que reintentos con la misma clave aumenten dos veces el mastery. Si se reintenta un envío parcial, completa las evidencias faltantes y recalcula los agregados.

Campos del agregado:

- `mastery_score`
- `times_seen`
- `last_rating`
- `last_seen_at`

No se guarda ninguna fecha de próxima revisión: el sistema no maneja la noción de "tarjeta vencida".

Finalmente, por cada `topicId` contenido en la tarjeta, escribe una fila en `learning_evidence` con usuario, materia, topic, recurso, rating y fecha.

Después recalcula `user_topic_progress` para todos los topics de la materia, incluidos los que todavía tienen coverage cero. El mastery es el promedio del mastery de recursos practicados asociados al topic; coverage es la proporción de flashcards y preguntas de cuestionario únicas practicadas respecto de todos esos recursos. También persiste recursos únicos, total de recursos, suma de intentos y última fecha de práctica.

### Evidencia de cuestionarios

Cada pregunta pertenece a uno o más topics persistidos en `quiz_question_topics`. Al seleccionar una opción, el frontend registra un `quiz_attempts` con la opción elegida, corrección, rating (`4` correcta, `0` incorrecta), sesión e idempotency key. Se escribe una fila de `learning_evidence` por topic de esa pregunta y se recalcula el mismo `user_topic_progress` compartido con flashcards. El mastery de una pregunta sigue la misma regla de primer rating ponderado y EMA que las tarjetas.

### Atomicidad e idempotencia

Las operaciones se hacen a través de varias peticiones REST independientes a Directus y no forman una transacción atómica. El UUID `idempotency_key` es único y evita duplicar el mismo intento si el cliente reintenta con la misma clave. No repara automáticamente una escritura parcial si el fallo ocurre después de guardar el intento.

---

## 7. Reglas matemáticas que existen en el backend

Las reglas están encapsuladas en `LearningProgressPolicy`, configurables mediante propiedades Spring con defaults.

### 7.1 Conversión del rating

Se calcula:

```text
score = rating / 4
```

Por tanto:

| Rating | Score |
|---:|---:|
| 0 | 0.00 |
| 1 | 0.25 |
| 2 | 0.50 |
| 3 | 0.75 |
| 4 | 1.00 |

### 7.2 Mastery por tarjeta

Los pesos por defecto son:

```text
retención del mastery anterior = 0.60
peso del rating actual         = 0.40
peso inicial del mastery       = 0.70
```

En el primer intento, el score se multiplica por el peso inicial:

```text
mastery inicial = score actual × 0.70
```

Por eso, un primer rating `4` produce mastery `0.70`, no `1.0`. Desde el segundo intento se aplica el EMA:

```text
nuevo mastery = mastery anterior × 0.60 + score actual × 0.40
```

Con ratings `4` consecutivos, el mastery progresa aproximadamente `0.70 → 0.82 → 0.892 → 0.9352`. Así, una única respuesta correcta no implica dominio absoluto y la evidencia repetida aumenta gradualmente la estimación.

Los pesos se configuran con `learning.mastery.initial-weight`, `learning.mastery.previous-weight` y `learning.mastery.current-weight`. Los dos pesos EMA deben sumar `1`.

Ejemplo: mastery anterior `0.80`, rating `0`:

```text
0.80 × 0.60 + 0.00 × 0.40 = 0.48
```

Así un único olvido reduce, pero no borra, el dominio anterior.

### 7.3 Sin fechas de revisión

No existen intervalos por rating. Todas las tarjetas del set son siempre elegibles para repaso; la prioridad sale únicamente del mastery (sección 7.5) y el cooldown evita repeticiones inmediatas. `last_seen_at` se conserva como dato histórico.

### 7.4 Cobertura

La cobertura mide qué proporción de los recursos únicos (tarjetas y preguntas) asociados a un topic se practicó al menos una vez:

```text
coverage = min(uniqueResourcesSeen / resourcesForTopic, 1)
```

Si el topic tiene 10 recursos y se practicaron 3 distintos, la cobertura es `0.30` (30%). Repetir un recurso no aumenta la cobertura; practicar otra tarjeta o pregunta única sí. Si el topic no tiene recursos, la cobertura es `0`.

La cobertura se calcula y persiste en `user_topic_progress` cuando se registra un intento. El mastery agregado del topic es el promedio del mastery de sus recursos practicados; el mastery de cada tarjeta/pregunta conserva su propia actualización EMA.

### 7.5 Peso para repaso

La política asigna a cada tarjeta un peso basado en su mastery:

```text
weight = max(minimumReviewWeight, (1 - mastery)²)
```

Cuanto menor es el mastery, mayor es el peso y, por lo tanto, mayor la probabilidad de que la tarjeta salga en la selección aleatoria. El peso **no es directamente un porcentaje**: se convierte en probabilidad al compararlo con la suma de los pesos de las demás tarjetas elegibles. El peso mínimo por defecto es `0.01`, para que una tarjeta dominada no llegue a probabilidad cero.

#### Ejemplo de selección ponderada

Supongamos que hay tres tarjetas y ninguna está en cooldown:

| Tarjeta | Mastery | Cálculo del peso | Peso |
|---|---:|---:|---:|
| A — tema poco dominado | `0.20` | `(1 - 0.20)²` | `0.64` |
| B — dominio intermedio | `0.50` | `(1 - 0.50)²` | `0.25` |
| C — muy dominada | `0.90` | `(1 - 0.90)²` | `0.01` (se aplica el mínimo) |

La suma es `0.90`. Aproximadamente, las probabilidades de selección quedan así:

```text
P(A) = 0.64 / 0.90 ≈ 71.1%
P(B) = 0.25 / 0.90 ≈ 27.8%
P(C) = 0.01 / 0.90 ≈  1.1%
```

La tarjeta A es mucho más probable, pero C conserva una posibilidad pequeña de aparecer. En cada selección el sorteo se repite; no se garantiza una secuencia fija.

#### Cómo se combina con el cooldown

`selectNext` considera todas las tarjetas candidatas y aplica un único filtro antes del sorteo:

- **Cooldown:** es una pausa corta para no volver a mostrar enseguida una tarjeta que acaba de aparecer. No cambia el mastery; sólo la excluye temporalmente mientras haya otras tarjetas disponibles. La configuración actual define una ventana de 3 tarjetas recientes; el caller debe pasar esos IDs en `recentlyShownCardIds`.

Ejemplo: A acaba de aparecer y queda dentro de las últimas 3 tarjetas mostradas. El cooldown la aparta temporalmente si hay otras tarjetas:

```text
Sale A → A entra en la lista de recientes
La siguiente selección excluye A → puede salir B
Luego B es reciente → puede salir C
Cuando A deja de estar dentro de la ventana de recientes → vuelve a competir según su peso
```

Si todas las tarjetas están marcadas como recientes, la implementación relaja el cooldown y sortea entre todas. Si no hay candidatas, devuelve `null`.

**Integración:** `GET /flashcards/review/next` carga tarjetas y mastery desde Directus y selecciona una con esta política. La UI ofrece el modo “Repaso ponderado” y manda las últimas tres tarjetas mostradas como cooldown; si todas quedan excluidas, la política relaja el cooldown.

---

## 8. Colecciones/tablas que prepara el esquema

`make dev` incluye un servicio `directus-schema` que espera a Directus saludable y aplica `scripts/directus-schema-migration.sh`. `make clean` elimina los volúmenes para probar un entorno nuevo; **destruye los datos persistidos**, así que no es un comando para uso normal.

El migrador crea colecciones base de aplicación y de progreso, campos y varias relaciones. Directus crea por su cuenta sus colecciones internas (`directus_users`, `directus_files`, `directus_folders`, roles, etc.).

Colecciones relevantes que aparecen en el esquema actual:

| Colección | Propósito actual/previsto |
|---|---|
| `subjects` | Materias, con usuario propietario |
| `flashcards` | Sets, incluyendo campo JSON `cards` |
| `quizzes` | Sets de preguntas en JSON que aportan evidencia por pregunta-topic |
| `topics` | Catálogo de temas por materia |
| `file_topics` | Asociación archivo-topic |
| `flashcard_cards` | Copia relacional de tarjetas con set, ID de dominio, texto, posición, lista JSON `topic_ids` y relación `primary_topic_id` |
| `flashcard_card_topics` | Relación UUID entre tarjeta, set y cada topic asociado |
| `quiz_question_topics` | Relación UUID entre cuestionario, pregunta y topic asociado |
| `flashcard_attempts` | Eventos históricos de autoevaluación |
| `quiz_attempts` | Eventos históricos de respuestas a preguntas, con corrección y rating |
| `learning_evidence` | Evidencia extensible por tipo de recurso y topic |
| `user_flashcard_progress` | Agregado actual por usuario y tarjeta |
| `user_topic_progress` | Mastery, coverage, recursos únicos, total de recursos, intentos y última práctica por usuario/topic |
| `study_sessions` | Sesiones activas o completadas asociadas a usuario y materia; pueden corresponder a un set de flashcards o cuestionario |

Las colecciones `flashcard_card_topics` y `quiz_question_topics` se escriben al crear recursos y son la fuente relacional para calcular cobertura por topic. Las sesiones se crean al abrir/generar un set o abrir un cuestionario; se completan al finalizar la práctica.

El migrador es aditivo: crea las colecciones y campos que faltan, pero no elimina colecciones de volúmenes existentes. La definición canónica está en `scripts/directus-schema-migration.sh`; el bootstrap Java `DirectusLearningSchemaService` y su inicializador fueron eliminados para evitar dos definiciones. Si una instalación existente tiene IDs/campos con tipos incompatibles, el migrador falla con una indicación para recrear el esquema. `make clean` elimina los volúmenes y sus datos, así que debe usarse sólo cuando se acepta perderlos.

### IDs

Todas las colecciones propias usan UUID como clave primaria. Las referencias a IDs propios también son UUID (materias, sets, topics, tarjetas, sesiones y evidencia); los IDs de `directus_users` y `directus_files` mantienen el UUID nativo de Directus. Los enteros se reservan para valores que no son IDs, como `rating`, `times_seen` y `position`.

La API Java representa los IDs de topics como `String`, igual que los demás UUID, y ya no convierte topic IDs a enteros.

---

## 9. Endpoints relacionados implementados

| Método y ruta | Estado |
|---|---|
| `POST /ai/files/flashcards` | Genera un set con topics IDs del catálogo de la materia y persiste el set/tarjetas |
| `GET /flashcards?subjectId=...` | Lista los sets de la materia |
| `PATCH /flashcards/{setId}?subjectId=...` | Renombra el set |
| `DELETE /flashcards/{setId}?subjectId=...` | Elimina el set |
| `POST /flashcards/{setId}/attempts?subjectId=...` | Registra un rating idempotente y actualiza progreso por tarjeta/topic |
| `POST /quizzes/{quizId}/study-sessions?subjectId=...` | Inicia una sesión de cuestionario |
| `POST /quizzes/{quizId}/attempts?subjectId=...` | Persiste una respuesta y evidencia para los topics de la pregunta |
| `POST /flashcards/study-sessions?subjectId=...` | Inicia una sesión para un set |
| `POST /flashcards/study-sessions/{sessionId}/complete?subjectId=...` | Completa una sesión del usuario |
| `POST /flashcards/study-sessions/{sessionId}/abandon?subjectId=...` | Marca como abandonada una sesión que se dejó antes de terminar |
| `GET /flashcards/progress?subjectId=...` | Consulta agregados de mastery y coverage por topic |
| `GET /flashcards/cards/{cardId}/attempts?subjectId=...` | Consulta histórico de ratings de una tarjeta |
| `GET /flashcards/review/next?subjectId=...&flashcardSetId=...` | Selecciona siguiente tarjeta por peso y cooldown |

Los endpoints de intentos requieren UUID de idempotencia y verifican que el `sessionId`, si se envía, pertenezca al usuario/materia y al set/cuestionario indicado, y siga activo.

---

## 10. Tests presentes

`LearningProgressPolicyTests` verifica reglas unitarias como:

- EMA después de existir mastery previo;
- cobertura basada en tarjetas y preguntas únicas respecto del total de recursos del topic;
- exclusión de una tarjeta reciente (cooldown) si existe otra candidata;
- relajación del cooldown cuando todas las candidatas son recientes;
- mayor peso de una tarjeta débil.

`FlashcardsPage.test.jsx` cubre generación, calificación y payload de intento con sesión/idempotencia, renombrado/eliminación y estado vacío. No hay todavía una prueba de integración que arranque Directus vacío y ejecute el flujo completo contra el schema real.

---

## 11. Resumen: implementación real vs. pendiente

### Ya implementado en el código

- El flujo de upload/indexado intenta clasificar PDFs y DOCX en topics.
- El catálogo está separado por materia; al clasificar un archivo la IA recibe sus IDs/nombres/descripciones y puede seleccionar topics existentes y proponer topics nuevos en la misma respuesta.
- El generador recibe únicamente los topics asociados a los archivos seleccionados (o todos los topics de la materia como fallback si ninguno tiene asociaciones) y valida los IDs devueltos.
- Las tarjetas generadas reciben UUIDs en el JSON del set.
- La UI ofrece ratings de `0` a `4` después de revelar la respuesta y manda el rating como evidencia de autoevaluación; no mide ni registra tiempo de respuesta.
- El backend valida ownership de usuario/materia/set/tarjeta.
- Cada calificación crea una fila de `flashcard_attempts`.
- El backend calcula el agregado `user_flashcard_progress` con EMA después del primer intento.
- Se crea una fila de `learning_evidence` por cada topic incluido en la tarjeta.
- Se persiste la relación por tarjeta-topic y pregunta-topic; los ratings de ambos recursos recalculan mastery promedio, coverage, recursos únicos, intentos y última práctica en `user_topic_progress`.
- Hay endpoints para consultar progreso e histórico de intentos; la UI muestra estadísticas por topic.
- Las sesiones se crean y completan desde la UI/API; la primera pasada exige rating antes de avanzar.
- El modo de repaso usa la selección ponderada, el mastery guardado y cooldown de tres tarjetas.
- Cada intento lleva una clave UUID única para que un reintento HTTP con la misma clave no duplique el registro.
- Compose tiene un job de migración de esquema antes de iniciar la API.

### Incompleto o no conectado todavía

- Incorporar un verificador semántico independiente (por ejemplo, embeddings) que detecte y corrija una selección incorrecta de la IA; hoy la selección semántica depende del clasificador, y el backend sólo verifica IDs y usa coincidencia normalizada exacta como respaldo al crear.
- Agregar una prueba de integración que valide el esquema y el flujo completo contra Directus real.
- Hacer atómicas las escrituras de intento, progreso y evidencia. La clave idempotente permite reintentos y completar algunas escrituras parciales, pero las llamadas REST a Directus todavía no comparten una transacción.

---

## 12. Archivos de referencia

- `dinaapi/src/main/java/com/studina/dinaapi/directus/FileController.java` — carga, indexado y disparo de detección de topics.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/TopicCatalogService.java` — catálogo, normalización, detección y asociación de topics.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/RagController.java` — endpoint y autorización para generar flashcards.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/RagQueryService.java` — generación RAG y validación de topic IDs.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/Flashcard.java` — modelo de tarjeta.
- `dinaapi/src/main/java/com/studina/dinaapi/directus/FlashcardSetService.java` — persistencia/listado de sets y copia de tarjetas relacional.
- `dinaapi/src/main/java/com/studina/dinaapi/directus/FlashcardController.java` — rutas de sets e intentos.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/FlashcardProgressService.java` — escritura de intentos, agregado tarjeta y evidencia.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/LearningProgressPolicy.java` — fórmulas de mastery, coverage y review.
- `scripts/directus-schema-migration.sh` y `compose.yaml` — esquema declarado y orden de inicialización Docker.
- `frontend/src/pages/FlashcardsPage.jsx` — biblioteca, estudio y evaluación visual.
- `frontend/src/pages/FlashcardsPage.css` — estilos de la evaluación.

---

## 13. Glosario breve

- **Materia / subject:** espacio de estudio del usuario.
- **Topic:** concepto canónico dentro de una materia, por ejemplo “Consistencia”.
- **Set:** grupo de flashcards generado desde uno o más documentos.
- **Tarjeta / card:** pregunta o término y su respuesta.
- **Rating:** autoevaluación ordinal de 0 a 4; no es un booleano correcto/incorrecto.
- **Attempt:** evento histórico que debe conservarse aunque existan intentos anteriores.
- **Mastery:** estimación normalizada entre 0 y 1.
- **Coverage / cobertura:** qué proporción de las tarjetas asociadas a un tema ya practicó.
- **Repaso ponderado:** selección aleatoria donde las tarjetas con menor mastery tienen más probabilidad de salir; no hay fechas de vencimiento.

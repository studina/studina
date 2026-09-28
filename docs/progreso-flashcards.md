# Progreso de aprendizaje en Studina

> **Estado actual:** Flashcards y cuestionarios alimentan el mismo progreso por topic, con el mismo modelo y las mismas fórmulas.

## 1. Idea general

Queremos guardar evidencia del progreso del estudiante. La idea es que los diferentes recursos (flashcards, quizzes, etc.) contribuyan al mismo conocimiento del usuario sobre un tema.

Flujo conceptual:

```text
Materia
  └── Topics canónicos
        ├── Archivos asociados
        └── Flashcards y preguntas asociadas
              └── Intentos de un usuario
                    ├── Histórico de eventos (append-only desde la API)
                    └── Dominio por recurso
                          └── Dominio por topic (promedio de sus recursos)
```

El modelo tiene tres capas, y cada una se deriva de la anterior:

| Capa | Dónde vive | Qué guarda |
|---|---|---|
| Eventos | `flashcard_attempts`, `quiz_attempts` | Qué respondió el usuario y cuándo. Histórico, no se edita |
| Dominio por recurso | `user_resource_progress` | Mastery actual de cada tarjeta o pregunta |
| Dominio por topic | `user_topic_progress` | Promedio de los recursos del topic que se practicaron |

Cualquiera de las dos capas derivadas se puede reconstruir desde los eventos. Tarjetas y preguntas son el mismo tipo de recurso para el progreso: cada una pertenece a exactamente un topic y tiene un único agregado.

La separación importante es:

- **Attempt / intento:** una respuesta concreta en un momento concreto. Se conserva como histórico.
- **Mastery / dominio:** estimación actual de qué tan bien parece conocer el contenido.
- **Coverage / cobertura:** qué proporción de las flashcards y preguntas de cuestionario asociadas a un topic ya practicó.

Mastery estima el desempeño y coverage describe cuánto contenido se recorrió.

No son métricas intercambiables: se puede tener mastery alto en los recursos practicados y, al mismo tiempo, coverage baja porque quedan muchos recursos del topic sin practicar.

---

## 2. Cómo se definen los topics

La lógica está al momento de subir un nuevo archivo en una materia. En `FileController.uploadFile` ocurre lo siguiente:

1. Se chequea el token del usuario y que la materia le pertenezca.
2. Se sube el archivo a Directus y lo asocia a la materia.
3. Para PDF o DOCX extrae texto y lo indexa en pgvector.
4. Si el indexado termina correctamente, invoca `TopicCatalogService.detectAndAssociate`.
5. El clasificador recibe la lista de topics de esa materia (si existe) y hasta 12.000 caracteres del texto extraído. Ahí, se le pide a la IA que nos diga qué IDs del catálogo ya existente representan los temas del documento y si hay conceptos centrales que no están cubiertos por el catálogo, que cree los topics nuevos que le parezcan.

El formato esperado es:

```json
{
  "existing_topic_ids": ["uuid-topic-existente"],
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

Al generar flashcards, el backend consulta las asociaciones `file_topics` de los archivos seleccionados y le pasa al generador únicamente la unión de esos topics. Si algunos archivos seleccionados no tienen topics, se ignoran; no se amplía el catálogo por esa razón. Sólo cuando ninguno de los archivos seleccionados tiene topics asociados se usa como fallback el catálogo completo de la materia y se registra un warning. El backend valida que los IDs devueltos pertenezcan a esa lista, pero esa validación no comprueba semánticamente que cada asignación sea correcta.

La generación de cuestionarios usa la misma selección. Cada pregunta recibe un UUID y exactamente un `topic_id` del conjunto permitido, igual que una flashcard, y se persiste en `quiz_questions.topic_id`.

---

## 3. Generación y persistencia de flashcards

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
      "topic_id": "uuid-topic-consistencia"
    },
    {
      "term": "¿Cómo se elige un líder en un sistema distribuido?",
      "definition": "Los nodos ejecutan un protocolo de elección para designar uno como coordinador. El mecanismo debe permitir elegir otro si el líder falla.",
      "topic_id": "uuid-topic-eleccion"
    }
  ]
}
```

Cada tarjeta recibe un UUID y exactamente un `topic_id` validado contra los topics asociados a los archivos seleccionados. Las tarjetas no tienen una lista de topics ni un topic primario separado.


---

## 4. Flujo de estudio: flashcards


1. La persona abre un set o genera uno.
2. La página muestra la tarjeta y permite revelar la respuesta.
3. Cuando la respuesta está revelada, le muestra 5 opciones:
   - `0`: No lo sabía.
   - `1`: Me sonaba, pero no pude recordarlo.
   - `2`: Lo recordé con bastante dificultad.
   - `3`: Lo sabía, pero dudé.
   - `4`: Lo sabía con seguridad.
4. Al elegir una opción, encola el envío del rating a la API.
5. El frontend pasa a la tarjeta siguiente sin esperar la respuesta: la escritura es fire-and-forget (ver 6). Si falla, lo único que ocurre es un `console.error`; la UI ya dio la tarjeta por calificada.
6. Los cinco botones quedan deshabilitados apenas se elige una opción, así que no se puede calificar dos veces la misma tarjeta desde esos controles.

### Navegación

En la primera pasada, **Siguiente** permanece deshabilitado hasta guardar el rating de la tarjeta actual; la UI también bloquea la navegación mientras espera la respuesta. El modo “Repaso ponderado” se finaliza con “Terminar repaso”. Salir de la pantalla sólo limpia el estado local.

Al calificar la última tarjeta —o al terminar un repaso ponderado— aparece la pantalla de cierre del set. Antes de leer el progreso espera a que drene la cola de escrituras: los ratings son fire-and-forget y el agregado por topic se recalcula dentro del POST del intento, así que sin esa espera los números serían los de antes de la ronda. La pantalla muestra el mastery promedio de los topics que tocan las tarjetas del set, el más flojo de esos topics, botones a los documentos de `source_file_ids` y una ronda de refuerzo.

**Ronda de refuerzo.** Pide `GET /flashcards/{setId}/mastery`, se queda con las tarjetas del set por debajo de `0.6` y las ordena de menor a mayor mastery. Ese orden es el que implica el peso `(1 - mastery)²` de la política, pero determinístico: no hay sorteo. Es una ronda finita sobre el mismo set, distinta del “Repaso ponderado”, que sortea indefinidamente hasta que la persona la corta. Si ninguna tarjeta queda por debajo del umbral no se abre una ronda vacía; la pantalla lo dice.

**No existe el concepto de sesión de estudio en el modelo.** Hubo una tabla `study_sessions` con estados `active`/`completed`/`abandoned` y una columna `session_id` en los intentos; las dos se eliminaron. El estado persistido no era confiable, porque dependía de que el usuario apretara un botón: al cerrar la pestaña la fila quedaba `active` para siempre.

Si en algún momento hacen falta métricas por sentada, se derivan agrupando los intentos de un usuario por cercanía temporal —por ejemplo, un corte cada vez que pasan más de 30 minutos entre dos intentos consecutivos—. Esa derivación es más fiel que un identificador guardado, por la misma razón que la duración derivada es más fiel que `completed_at`: un identificador de sesión sobrevive a los ratos de inactividad, y el corte por tiempo no.

Cada intento lleva un `attemptId`, un UUID que genera la UI (ver 5).

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
  "attemptId": "UUID-del-intento"
}
```

El endpoint de `FlashcardController`:

1. Resuelve el usuario desde el bearer token.
2. Verifica ownership de la materia.
3. Busca el set y comprueba que pertenece a la materia indicada.
4. Busca en `flashcard_cards` una tarjeta con ese ID dentro del set. Si no pertenece a ese set, rechaza el request.
5. Llama a `FlashcardProgressService.recordAttempt`. El servicio valida que el rating exista y esté en rango `0..4`. Después agrega una fila nueva en `flashcard_attempts`, donde guarda:


- `user_id`
- `subject_id`
- `flashcard_set_id`
- `card_id`
- `attempt_id` (UUID que genera el frontend; identifica la acción del usuario, no la fila)
- `rating`
- `created_at`

Después reconstruye el agregado de `user_resource_progress` desde el histórico de intentos, evitando que un reintento secuencial con la misma clave vuelva a aplicar el rating. Si se reintenta un envío parcial, recalcula los agregados que hayan quedado sin escribir. El endpoint no ofrece una operación para editar intentos, pero la inmutabilidad no está impuesta por una restricción de base de datos: un administrador de Directus todavía podría modificarlos o borrarlos.

Campos del agregado:

- `mastery_score`
- `times_seen`
- `last_seen_at`

No se guarda ninguna fecha de próxima revisión: el sistema no maneja la noción de "tarjeta vencida".

El topic no se copia en el intento: la pertenencia de la tarjeta a su topic ya vive en `flashcard_cards.topic_id`, y el recálculo la resuelve por join.

Después recalcula `user_topic_progress` para los topics de la materia del usuario, incluidos los que todavía tienen coverage cero. El mastery es el promedio del mastery de los recursos practicados asociados al topic. También persiste recursos únicos, suma de intentos y última fecha de práctica. La cobertura y el total de recursos del topic no se guardan: son derivados y se calculan al leer el progreso (ver 7.4).

### Progreso de cuestionarios

Cada pregunta pertenece a exactamente un topic, persistido en `quiz_questions.topic_id`. Al seleccionar una opción, el frontend registra un `quiz_attempts` con la opción elegida, corrección y `attempt_id`. Una respuesta de opción múltiple es un hecho booleano, así que la columna `is_correct` es la fuente y el score se deriva de ella (`1.0` correcta, `0.0` incorrecta) para entrar en el mismo EMA que usan las tarjetas.

Después se reconstruye el agregado de esa pregunta en `user_resource_progress` y se recalcula el mismo `user_topic_progress` compartido con flashcards.

Hubo un momento en que una pregunta podía pertenecer a varios topics, y la evidencia se guardaba y reproducía una vez por topic, lo que duplicaba `total_attempts` e inflaba el mastery. Hoy eso no puede ocurrir por dos razones independientes: hay un único agregado por recurso, y un recurso pertenece a un solo topic.

## 6. Atomicidad y reintentos

### 6.1 `id` vs. `attempt_id`

Las tablas de intentos tienen dos identificadores y conviene no confundirlos:

- **`id`** lo genera Directus al insertar la fila. Identifica **el registro**.
- **`attempt_id`** lo genera la UI antes de mandar el POST. Identifica **la acción del usuario**: una calificación, una respuesta. Si el mismo POST se reenvía tres veces, las tres llevan el mismo `attempt_id` y debe quedar una sola fila.

Esa distinción es lo que hace correcto el mastery. Como `ResourceProgressService` lo reconstruye reproduciendo todo el historial, una fila duplicada equivale a aplicar el EMA dos veces, sin forma de detectarlo ni deshacerlo. El backend busca el `attempt_id` antes de insertar y, si ya existe, verifica que venga con la misma tarjeta, materia, set y rating, y no inserta nada. Por debajo, el índice único `(user_id, attempt_id)` es la red de seguridad para dos requests concurrentes que pasen juntos esa verificación.

Las escrituras del frontend son fire-and-forget —la UI marca la tarjeta como calificada sin esperar la respuesta—, así que los reenvíos son un escenario real, no teórico.

### 6.2 Atomicidad

Las operaciones se hacen a través de varias peticiones REST independientes a Directus y no forman una transacción atómica. `attempt_id` es único por usuario en las colecciones de intentos y protege principalmente los reintentos secuenciales con la misma clave; la recuperación de escrituras parciales ocurre sólo si el cliente vuelve a enviar la solicitud. No hay reparación automática en segundo plano y dos solicitudes concurrentes con la misma clave todavía pueden competir y producir un error. Para cuestionarios, un reintento encontrado por clave valida que coincidan cuestionario, pregunta y opción, y recalcula el agregado.

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

La cobertura **no se persiste**: se calcula al leer el progreso, en `GET /flashcards/progress`. El total de recursos de un topic es un dato del topic, no del usuario, así que guardar una copia por usuario lo dejaba desactualizado cada vez que se agregaba un set nuevo a la materia. Lo que sí se persiste en `user_topic_progress` es `unique_resources_seen`, y la cobertura sale de dividirlo por la cantidad actual de recursos del topic.

El mastery agregado del topic es el promedio del mastery de sus recursos practicados; el mastery de cada tarjeta/pregunta conserva su propia actualización EMA en `user_resource_progress`.

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

Las tarjetas sin historial también participan: la API les asigna mastery `0.0` por defecto, por lo que reciben el peso máximo de la fórmula. El endpoint recibe IDs recientes del caller; no persiste por sí mismo la ventana de cooldown.

**Integración:** `GET /flashcards/review/next` carga tarjetas y mastery desde Directus y selecciona una con esta política. La UI ofrece el modo “Repaso ponderado” y manda las últimas tres tarjetas mostradas como cooldown; si todas quedan excluidas, la política relaja el cooldown.

---

## 8. Colecciones/tablas que prepara el esquema

`make dev` incluye un servicio `directus-schema` que espera a Directus saludable y aplica `scripts/directus-schema-migration.sh`. `make clean` elimina los volúmenes para probar un entorno nuevo; **destruye los datos persistidos**, así que no es un comando para uso normal.

El migrador crea colecciones base de aplicación y de progreso, campos y varias relaciones. Directus crea por su cuenta sus colecciones internas (`directus_users`, `directus_files`, `directus_folders`, roles, etc.).

Colecciones relevantes que aparecen en el esquema actual:

| Colección | Propósito actual/previsto |
|---|---|
| `subjects` | Materias, con usuario propietario |
| `flashcards` | Sets de flashcards (título, dificultad, archivos de origen) |
| `quizzes` | Cuestionarios (título, dificultad, archivos de origen) |
| `topics` | Catálogo de temas por materia |
| `file_topics` | Asociación archivo-topic |
| `flashcard_cards` | Tarjetas: set, texto, posición y un `topic_id` obligatorio. Única representación de una tarjeta |
| `quiz_questions` | Preguntas: cuestionario, enunciado, opciones, opción correcta, explicación, `topic_id` y posición. Única representación de una pregunta |
| `flashcard_attempts` | Eventos históricos de autoevaluación |
| `quiz_attempts` | Eventos históricos de respuestas a preguntas, con la opción elegida y si fue correcta |
| `user_resource_progress` | Agregado actual por usuario y recurso (tarjeta o pregunta) |
| `user_topic_progress` | Mastery, recursos únicos, intentos y última práctica por usuario/topic |

`flashcard_cards.topic_id` y `quiz_questions.topic_id` son la fuente de pertenencia topic, con la misma forma en los dos casos: un recurso, un topic. Se usan para atribuir el mastery de cada recurso a su topic y para calcular cobertura.

El contenido se guarda en un solo lugar: las tarjetas viven únicamente en `flashcard_cards` y las preguntas en `quiz_questions`. `flashcards` y `quizzes` ya no llevan un campo JSON con una segunda copia. La API sigue devolviendo las tarjetas bajo `cards` y las preguntas bajo `questions` porque los servicios reconstruyen esos objetos al leer.

El migrador es aditivo: crea las colecciones y campos que faltan, pero no elimina colecciones de volúmenes existentes. La definición canónica está en `scripts/directus-schema-migration.sh`; el bootstrap Java `DirectusLearningSchemaService` y su inicializador fueron eliminados para evitar dos definiciones. Si una instalación existente tiene IDs/campos con tipos incompatibles, el migrador falla con una indicación para recrear el esquema. `make clean` elimina los volúmenes y sus datos, así que debe usarse sólo cuando se acepta perderlos.

Las colecciones `flashcards` y `quizzes` declaran y reciben `date_created`, usado para ordenar sus listados. El migrador declara las relaciones de archivos, usuarios, sets, tarjetas y topics que corresponden a claves UUID. También instala restricciones únicas compuestas para evitar repetir una asociación archivo-topic, para mantener un único agregado por usuario/recurso y usuario/topic, y para que el `attempt_id` sea único **por usuario** en cada colección de intentos. Ahora que las preguntas tienen tabla propia, `quiz_attempts.question_id` y `quiz_questions.topic_id` sí son claves foráneas. La única referencia que no puede serlo es `user_resource_progress.resource_id`, por ser polimórfica.

### IDs

Todas las colecciones propias usan UUID como clave primaria. Las referencias a IDs propios también son UUID (materias, sets, topics, tarjetas y recursos); los IDs de `directus_users` y `directus_files` mantienen el UUID nativo de Directus. Los enteros se reservan para valores que no son IDs, como `rating`, `times_seen` y `position`.

Una tarjeta y una pregunta se identifican por el `id` de su fila; no hay una segunda columna con el mismo valor.

La API Java representa los IDs de topics como `String`, igual que los demás UUID, y ya no convierte topic IDs a enteros.

---

## 9. Endpoints relacionados implementados

| Método y ruta | Estado |
|---|---|
| `POST /ai/files/flashcards` | Genera un set con topics asociados a los archivos seleccionados (fallback al catálogo de materia sólo si ninguno tiene topics) y persiste el set/tarjetas |
| `POST /ai/files/quizzes` | Igual para cuestionarios: persiste el cuestionario y sus preguntas |
| `GET /flashcards?subjectId=...` | Lista los sets de la materia, con sus tarjetas |
| `PATCH /flashcards/{setId}?subjectId=...` | Renombra el set |
| `DELETE /flashcards/{setId}?subjectId=...` | Elimina el set y recalcula el progreso de la materia |
| `POST /flashcards/{setId}/attempts?subjectId=...` | Registra un rating idempotente y actualiza progreso por tarjeta/topic |
| `GET /quizzes?subjectId=...` | Lista los cuestionarios de la materia, con sus preguntas |
| `PATCH /quizzes/{quizId}?subjectId=...` | Renombra el cuestionario |
| `DELETE /quizzes/{quizId}?subjectId=...` | Elimina el cuestionario y recalcula el progreso de la materia |
| `POST /quizzes/{quizId}/attempts?subjectId=...` | Persiste una respuesta y recalcula el progreso de la pregunta y de su topic |
| `GET /flashcards/progress?subjectId=...` | Consulta agregados de mastery y coverage por topic |
| `GET /flashcards/cards/{cardId}/attempts?subjectId=...` | Consulta histórico de ratings de una tarjeta |
| `GET /flashcards/review/next?subjectId=...&flashcardSetId=...` | Selecciona siguiente tarjeta por peso y cooldown |
| `GET /flashcards/{setId}/mastery?subjectId=...` | Devuelve el mastery de cada tarjeta del set; una tarjeta sin historial vale `0.0` |

Los endpoints de intentos requieren un `attemptId` con formato UUID. La pertenencia de usuario, materia y set/cuestionario la verifican `requireUserId`, `requireSubjectOwnership` y `requireSetOwnership`/`requireQuizOwnership`, siempre en el controller. En reintentos idempotentes se valida que la clave corresponda al intento y al contexto original.

---

## 10. Tests presentes

`LearningProgressPolicyTests` y `TopicProgressSnapshotTests` verifican reglas unitarias como:

- EMA después de existir mastery previo, y que una respuesta de cuestionario use exactamente el mismo EMA que un rating;
- cobertura basada en tarjetas y preguntas únicas respecto del total de recursos del topic;
- que un recurso aporte un solo intento a su topic (regresión del doble conteo);
- exclusión de una tarjeta reciente (cooldown) si existe otra candidata;
- relajación del cooldown cuando todas las candidatas son recientes;
- mayor peso de una tarjeta débil.

`GeneratedResourcePolicyTests` cubre la validación de lo que devuelve la IA. Como tarjetas y preguntas pasan por la misma política, las reglas de topic e identidad se verifican una sola vez para ambas —id asignado por el servidor y distinto por recurso, topic obligatorio y dentro del catálogo, lote incompleto o ausente rechazado, lote excedente truncado— y sólo se prueban por separado las reglas de contenido: término y definición en las tarjetas; cantidad de opciones, opción correcta existente y textos no vacíos en las preguntas. También se verifica que las dos serialicen un único `topic_id` en snake_case y que rechacen el payload viejo con `topic_ids`.

`TopicCatalogServiceTests` y `TopicClassificationResolverTests` cubren la detección de topics de la sección 2: normalización de nombres, reutilización de un topic existente por coincidencia exacta, rechazo de IDs que no pertenecen a la materia y tope de 3 topics nuevos por documento.

`FlashcardsPage.test.jsx` cubre generación, calificación y payload de intento con `attemptId`, renombrado/eliminación y estado vacío. `QuizPage.test.jsx` cubre el payload de respuesta de quiz. Ambos verifican además que no se llame a ningún endpoint de sesiones. Las pruebas de frontend mockean las llamadas HTTP; no hay todavía una prueba de integración que arranque Directus vacío y ejecute el flujo completo contra el schema real, ni pruebas de integración de los servicios de persistencia.

---

## 11. Resumen: implementación real vs. pendiente

### Ya implementado en el código

- El flujo de upload/indexado intenta clasificar PDFs y DOCX en topics.
- El catálogo está separado por materia; al clasificar un archivo la IA recibe sus IDs/nombres/descripciones y puede seleccionar topics existentes y proponer topics nuevos en la misma respuesta.
- El generador recibe únicamente los topics asociados a los archivos seleccionados (o todos los topics de la materia como fallback si ninguno tiene asociaciones) y valida los IDs devueltos.
- Cada tarjeta generada recibe un UUID y exactamente un `topic_id`, validado contra los topics permitidos, y se guarda en `flashcard_cards`.
- La UI ofrece ratings de `0` a `4` después de revelar la respuesta y manda el rating a la API; no mide ni registra tiempo de respuesta.
- El backend valida ownership de usuario/materia/set/tarjeta.
- Cada calificación crea una fila de `flashcard_attempts`.
- El backend calcula el agregado `user_resource_progress` con EMA después del primer intento, con el mismo código para tarjetas y preguntas.
- Se persiste un `topic_id` por tarjeta en `flashcard_cards` y uno por pregunta en `quiz_questions`; los ratings de ambos recursos recalculan mastery promedio, recursos únicos, intentos y última práctica en `user_topic_progress`.
- Hay endpoints para consultar progreso e histórico de intentos.
- La primera pasada exige rating antes de avanzar.
- El modo de repaso usa la selección ponderada, el mastery guardado y cooldown de tres tarjetas.
- Al terminar un set, la UI muestra mastery promedio y topic más flojo de ese set, acceso a sus documentos de origen y una ronda de refuerzo con las tarjetas por debajo del umbral.
- Cada intento lleva una clave UUID única para que un reintento HTTP con la misma clave no duplique el registro.
- Compose tiene un job de migración de esquema antes de iniciar la API.

### Incompleto o no conectado todavía

- Incorporar un verificador semántico independiente (por ejemplo, embeddings) que detecte y corrija una selección incorrecta de la IA; hoy la selección semántica depende del clasificador, y el backend sólo verifica IDs y usa coincidencia normalizada exacta como respaldo al crear.
- Agregar una prueba de integración que valide el esquema y el flujo completo contra Directus real.
- Construir una pantalla de progreso por topic a nivel materia. `GET /flashcards/progress` ya lo consume la pantalla de cierre de un set, pero acotado a los topics de ese set; no hay todavía una vista del progreso completo de la materia.
- Hacer atómicas las escrituras de intento y progreso. La clave idempotente permite reintentos y completar algunas escrituras parciales, pero las llamadas REST a Directus todavía no comparten una transacción.
- Medir el tiempo de respuesta. La propuesta del trabajo lo menciona como variable de personalización, pero hoy no se registra en ningún lado.

---

## 12. Archivos de referencia

- `dinaapi/src/main/java/com/studina/dinaapi/directus/FileController.java` — carga, indexado y disparo de detección de topics.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/TopicCatalogService.java` — catálogo, normalización, detección y asociación de topics.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/RagController.java` — endpoints y autorización para generar flashcards y cuestionarios.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/RagQueryService.java` — generación RAG y prompts.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/GeneratedResourcePolicy.java` — validación de lo que devuelve la IA y asignación de IDs, compartida por tarjetas y preguntas.
- `dinaapi/src/main/java/com/studina/dinaapi/ai/rag/Flashcard.java` y `QuizQuestion.java` — modelos de tarjeta y pregunta; ambos implementan `GeneratedResource`.
- `dinaapi/src/main/java/com/studina/dinaapi/directus/FlashcardSetService.java` — persistencia y listado de sets, y lectura de las tarjetas desde `flashcard_cards`.
- `dinaapi/src/main/java/com/studina/dinaapi/directus/QuizSetService.java` — lo mismo para cuestionarios y `quiz_questions`.
- `dinaapi/src/main/java/com/studina/dinaapi/directus/FlashcardController.java` y `QuizController.java` — rutas de sets, cuestionarios e intentos.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/FlashcardProgressService.java` — escritura de intentos de flashcards.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/QuizProgressService.java` — escritura de respuestas de cuestionario.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/ResourceProgressService.java` — agregado por recurso, compartido por tarjetas y preguntas.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/LearningAnalyticsService.java` — agregado por topic y selección de repaso.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/TopicProgressSnapshot.java` — agregación de un topic a partir del progreso de sus recursos.
- `dinaapi/src/main/java/com/studina/dinaapi/learning/LearningProgressPolicy.java` — fórmulas de mastery, coverage y review.
- `scripts/directus-schema-migration.sh` y `compose.yaml` — esquema declarado y orden de inicialización Docker.
- `frontend/src/pages/FlashcardsPage.jsx` y `QuizPage.jsx` — biblioteca, estudio y evaluación visual.
- `frontend/src/lib/uuid.js` — generación del `attemptId`.
- `frontend/src/lib/openFile.js` — abre un documento en otra pestaña con el token; los endpoints de archivo exigen `Authorization`, así que no alcanza con un link.
- `frontend/src/pages/FlashcardsPage.css` — estilos de la evaluación.

---

## 13. Glosario breve

- **Materia / subject:** espacio de estudio del usuario.
- **Topic:** concepto canónico dentro de una materia, por ejemplo “Consistencia”.
- **Set:** grupo de flashcards generado desde uno o más documentos.
- **Tarjeta / card:** pregunta o término y su respuesta.
- **Recurso:** una tarjeta o una pregunta de cuestionario. Para el progreso son lo mismo: pertenecen a un topic, tienen un agregado propio y comparten las fórmulas.
- **Rating:** autoevaluación ordinal de 0 a 4; no es un booleano correcto/incorrecto.
- **Attempt:** evento histórico que la API agrega sin ofrecer edición; la base de datos no impide que un administrador lo modifique o borre.
- **Mastery:** estimación normalizada entre 0 y 1. Se guarda por recurso en `user_resource_progress` y se promedia por topic en `user_topic_progress`.
- **Coverage / cobertura:** qué proporción de las flashcards y preguntas de cuestionario asociadas a un topic ya practicó.
- **Repaso ponderado:** selección aleatoria donde las tarjetas con menor mastery tienen más probabilidad de salir; no hay fechas de vencimiento.

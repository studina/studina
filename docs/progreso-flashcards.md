# Progreso de aprendizaje en Studina

## Idea general

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
| Eventos | `flashcard_attempts`, `quiz_attempts` | Qué respondió el usuario y cuándo. Histórico: sólo se puede corregir la nota de un intento dentro de la ronda en curso |
| Dominio por recurso | `user_resource_progress` | Mastery actual de cada tarjeta o pregunta |
| Dominio por topic | `user_topic_progress` | Promedio de los recursos del topic que se practicaron |

Importante entender la diferencia:

- **Attempt / intento:** una respuesta concreta en un momento concreto. Se guarda como dato histórico y listo.
- **Mastery / dominio:** estimación actual de qué tan bien parece conocer el contenido.
- **Coverage / cobertura:** qué proporción de las flashcards y preguntas de cuestionario asociadas a un topic ya practicó el usuario (ej: hay 3 flashcards y 3 preguntas relacionadas al topic A, el usuario tiene un 50% de cobertura sobre el tema A [más allá de si su mastery es bueno o malo]).

Mastery estima el desempeño y coverage describe cuánto contenido se recorrió.

No son métricas intercambiables: se puede tener mastery alto en los recursos practicados y, al mismo tiempo, coverage baja porque quedan muchos recursos del topic sin practicar.

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
  "existing_topic_ids": ["uuid-topic-existente"],
  "new_topics": [
    {
      "name": "Elección de líder",
      "description": "Mecanismos para seleccionar un nodo coordinador en un sistema distribuido."
    }
  ]
}
```

Por ejemplo, si el catálogo de temas de la materia ya contiene `Consistencia eventual/fuerte` y el documento habla de consistencia eventual, la IA debería devolver el ID de ese topic existente. Si el mismo documento también introduce un concepto central no cubierto, como `Elección de líder`, puede devolver además esa propuesta en `new_topics`.

6. El backend resuelve la clasificación de forma segura:

   1. Comprueba que cada ID de `existing_topic_ids` exista en el catálogo de esa materia. Rechaza IDs desconocidos o de otra materia; no acepta que la IA invente IDs.
   2. Conserva los topics existentes seleccionados, sin duplicarlos si la IA repite un ID.
   3. Valida las propuestas nuevas: deben tener nombre y no pueden ser más de 3 por documento.
   4. Crea cada topic nuevo en `topics`. Como protección secundaria, `createOrReuse` compara su nombre normalizado con el catálogo; una coincidencia exacta reutiliza el topic en vez de duplicarlo.
   5. Combina los topics existentes y los recién creados, elimina IDs repetidos y asocia cada resultado al archivo mediante `file_topics`.

   En resumen, el clasificador decide **qué conceptos ya están representados** y **cuáles faltan**; el backend valida esa decisión y persiste las asociaciones.

7. Al finalizar, queda una relación `file_topics` por cada topic existente seleccionado o nuevo creado. Si la IA no devuelve ningún topic existente ni ninguna propuesta nueva válida, la clasificación se considera fallida y se loggea un warning, pero el file lo cargamos igual.

### Reintento cuando el proveedor se cae

La llamada al clasificador se reintenta **3 veces con backoff exponencial** (1s y después 2s), configurable con `ai.classification.max-attempts` y `ai.classification.retry-delay-ms`.

Hizo falta porque un fallo de medio segundo dejaba una inconsistencia permanente: subir un archivo mientras el modelo estaba saturado devolvía `503 This model is currently experiencing high demand`, el archivo se cargaba igual y quedaba **sin temas para siempre**. La única forma de recuperarlo era borrarlo y volver a subirlo.

Sólo se reintenta lo que se resuelve solo —`503`, "overloaded", "unavailable", "high demand"—, según `AiProviderErrorMapper.isTransient`. **La cuota agotada queda afuera a propósito**: un `429` no se arregla esperando dos segundos, así que reintentarlo sólo agrega latencia y más llamadas contra el límite. Un `400` o un `401` fallan en el acto.

El bucle vive en `AiRetry`, separado del servicio, porque adentro dependía de la cadena fluida del `ChatClient` y no se podía probar sin mockearla entera. Recibe la espera inyectada, así que los tests verifican el backoff sin tardar lo que tarda.

El reintento achica la ventana pero no la cierra: si el modelo está caído los tres intentos, el archivo vuelve a quedar sin temas y en silencio. Queda pendiente mostrar el `topicDetectionWarning` que ya devuelve la API —hoy el frontend lo ignora— y ofrecer una reclasificación que no obligue a borrar el archivo.

### Temas disponibles para generar tarjetas

Al generar flashcards o quizzes, solo le pasamos a la IA la lista de los temas que estén asociados a los archivos que haya seleccionado el usuario al momento de crear el recurso. 

Si (por alguna extraña razón) ninguno de los archivos seleccionados tiene topics asociados (porque falló la asociasión al momento de acrga de esos archivos [de nuevo, un caso raro]) se manda tooodo el listado de temas de la materia y se loggea un warning. 

Esto es para que igualmente se le pueda asignar un tema a cada elemento (sea card o pregunta). 


---

## Generación y persistencia de flashcards

Al momento de generar, le pegamos a este endpoint y ocurre lo siguiente:

```text
POST /api/ai/files/flashcards
  → RagController.generateFlashcards
  → autorización de usuario, materia y archivos
  → topics asociados a los archivos seleccionados
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

Después, desde el back, a cada tarjeta le asignamos un UUID y las persistimos.


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
4. Al elegir una opción, se encola el envío del rating a la API. Lo hacemos asincronicamente para que no quede cargando y el usuario tenga que esperar para avanzar a la siguiente card.
5. El frontend pasa a la tarjeta siguiente sin esperar la respuesta: la escritura es fire-and-forget. Si falla, la UI ya dio la tarjeta por calificada y no hay reintento, así que se loggea un warning con el `cardId`, el `attemptId` y el rating: sin ese contexto no habría forma de saber después cuál calificación se perdió. Además se marca la ronda como incompleta, y al terminar la pantalla de cierre avisa que las métricas pueden no estar actualizadas.

   Del lado del backend, si el intento sí llega pero se rechaza, `FlashcardController` loggea un warning con el motivo; un fallo inesperado queda como error.
6. La opción elegida queda marcada y se puede cambiar: volviendo con **Anterior** a cualquier tarjeta ya calificada de la ronda, elegir otra nota manda un `PATCH` que **corrige el intento en su lugar**.

   No es lo mismo que calificar de nuevo. Como el mastery se reconstruye reproduciendo todo el historial, un intento extra aplicaría el EMA dos veces: equivocarse con `0` y después poner `4` deja `0.40`, mientras que corregir ese `0` a `4` deja `0.70`. Un click errado no es una autoevaluación, así que corregirlo hace el registro más fiel, no menos.

   Corregir no cuesta nada extra: `recalculate` ya rehace el agregado desde cero en cada intento, así que un `PATCH` dispara exactamente el mismo trabajo que un `POST`. Si el intento original nunca llegó a guardarse, el `PATCH` devuelve 404 y el frontend lo crea con la nota corregida, recuperando de paso la escritura perdida.

### Navegación

**Siguiente** permanece deshabilitado hasta guardar el rating de la tarjeta actual. 

Una ronda de repaso puede traer la misma tarjeta más de una vez y cada aparición se califica por separado, registrando su propio intento.

Al calificar la última tarjeta, o al terminar un repaso, aparece la pantalla de cierre del set. Antes de mostrar el progreso espera a que se terminen de cargar las respuestas del usuario y calcular los cores.

Una vez que carga, la pantalla muestra el mastery promedio de los topics que tocan las tarjetas del set, el más flojo de esos topics, botones a los documentos de `source_file_ids` y la posibilidad de hacer otra ronda de refuerzo.

**Repaso.** El botón “Repasar” le pega a `GET /flashcards/{setId}/review-round` que devuelve la lista de cards a repasar completa y ya ordenada. 

---

## Endpoint para registrar un intento


```text
POST /flashcards/{flashcardSetId}/attempts
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
2. Busca el set, saca de ahí su materia y verifica que sea del usuario (`requireOwnedSet`).
3. Busca en `flashcard_cards` una tarjeta con ese ID dentro del set. Si no pertenece a ese set, rechaza el request.
4. Llama a `FlashcardProgressService.recordAttempt`. El servicio valida que el rating exista y esté en rango `0..4`. Después agrega una fila nueva en `flashcard_attempts`, donde guarda:


- `user_id`
- `subject_id`
- `flashcard_set_id`
- `card_id`
- `attempt_id` (UUID que genera el frontend; identifica la acción del usuario, no la fila)
- `rating`
- `created_at`

Después reconstruye el agregado de `user_resource_progress` desde el histórico de intentos, evitando que un reintento secuencial con la misma clave vuelva a aplicar el rating. Si se reintenta un envío parcial, recalcula los agregados que hayan quedado sin escribir.

Para corregir una nota existe `PATCH /flashcards/{setId}/attempts/{attemptId}`, que cambia **sólo** el `rating` de esa fila. `created_at` no se toca: el replay ordena por esa fecha, así que moverla reordenaría el historial y cambiaría el mastery por un motivo ajeno a la corrección. Después recalcula igual que el POST. Fuera de eso, la inmutabilidad no está impuesta por una restricción de base de datos: un administrador de Directus todavía podría modificar o borrar intentos.

Campos del agregado:

- `mastery_score`
- `times_seen`
- `last_seen_at`

No se guarda ninguna fecha de próxima revisión: el sistema no maneja la noción de "tarjeta vencida".

El topic no se copia en el intento: la pertenencia de la tarjeta a su topic ya vive en `flashcard_cards.topic_id`, y el recálculo la resuelve por join.

Después recalcula `user_topic_progress`, pero **sólo del topic de esa tarjeta**. Calificar cambia el mastery de un recurso, y un recurso pertenece a un único topic, así que reconstruir los demás sería reescribirlos con el valor que ya tenían: es una escritura en lugar de una por topic de la materia. El mastery es el promedio del mastery de los recursos practicados asociados al topic; también persiste recursos únicos, suma de intentos y última fecha de práctica.

El recálculo completo de la materia se conserva para cuando se borra un set o un cuestionario, que sí afecta a varios topics a la vez. Sólo esa ruta hace además el mantenimiento de filas: crear una en cero para los topics que nadie practicó y borrar las de topics que ya no existen.

Por eso un topic sin practicar puede no tener fila, y entonces no entra en los promedios en vez de entrar como 0%. Es la misma regla que usa el armado de la ronda (7.5): no hay evidencia no es lo mismo que mastery 0.

La cobertura y el total de recursos del topic no se guardan: son derivados y se calculan al leer el progreso, y sólo si se piden (ver 7.4).

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

**Reintento y corrección no son lo mismo.** Un `POST` reenviado con el mismo `attempt_id` pero otro rating se **rechaza**: un reintento de red tiene que traer lo mismo, y aceptar otra nota ahí sería no poder distinguir un reenvío de un cambio. Cambiar la nota a propósito es otra operación, con otro verbo y otra ruta: `PATCH /flashcards/{setId}/attempts/{attemptId}`. La asimetría es deliberada —el `POST` es idempotente, el `PATCH` es intencional— y está comentada en el código, porque a primera vista parece una contradicción.

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

No existen intervalos por rating. Todas las tarjetas del set son siempre elegibles para repaso; la prioridad sale únicamente del mastery (sección 7.5). `last_seen_at` se conserva como dato histórico.

### 7.4 Cobertura

La cobertura mide qué proporción de los recursos únicos (tarjetas y preguntas) asociados a un topic se practicó al menos una vez:

```text
coverage = min(uniqueResourcesSeen / resourcesForTopic, 1)
```

Si el topic tiene 10 recursos y se practicaron 3 distintos, la cobertura es `0.30` (30%). Repetir un recurso no aumenta la cobertura; practicar otra tarjeta o pregunta única sí. Si el topic no tiene recursos, la cobertura es `0`.

La cobertura **no se persiste**: se calcula al leer el progreso, en `GET /flashcards/progress`. El total de recursos de un topic es un dato del topic, no del usuario, así que guardar una copia por usuario lo dejaba desactualizado cada vez que se agregaba un set nuevo a la materia. Lo que sí se persiste en `user_topic_progress` es `unique_resources_seen`, y la cobertura sale de dividirlo por la cantidad actual de recursos del topic.

Contar esos recursos obliga a listar **todas** las tarjetas y **todas** las preguntas de la materia, así que es **opt-in**: `GET /flashcards/progress` no devuelve `coverage` ni `total_resources` salvo que se pida con `coverage=true`. La pantalla de cierre de un set no los usa y por lo tanto no los pide.

El mastery agregado del topic es el promedio del mastery de sus recursos practicados; el mastery de cada tarjeta/pregunta conserva su propia actualización EMA en `user_resource_progress`.

### 7.5 Peso para repaso y armado de la ronda

La política asigna a cada tarjeta un peso basado en su mastery:

```text
weight = max(minimumReviewWeight, (1 - mastery)²)
```

Cuanto menor es el mastery, mayor es el peso. Ese peso **no es una probabilidad**: define **cuántas veces** entra la tarjeta en una ronda de repaso.

```text
repeticiones = 1 + round(weight × (maxReviewRepetitions - 1))
```

Con el default `maxReviewRepetitions = 3`:

| Mastery | Peso | Veces en la ronda |
|---:|---:|---:|
| `0.00` – `0.13` | ≥ `0.75` | 3 |
| `0.13` – `0.50` | `0.25` – `0.75` | 2 |
| > `0.50` | < `0.25` | 1 |

**Una tarjeta sin historial entra una sola vez.** No es lo mismo que un mastery de `0.0`: en un caso no hay evidencia y en el otro la evidencia dice que la persona no la sabe. Repetir tres veces algo que nunca se vio no es reforzar. De ahí se desprende la propiedad que importa: **la ronda de un set recién generado es el set entero, una vez cada tarjeta y en su orden original**. La primera pasada no se altera.

#### Armado por pasadas

`buildReviewRound` ordena las tarjetas de menor a mayor mastery —una sin practicar cuenta como `0.0` para el orden, así que el material nuevo va adelante— y arma la ronda en pasadas: primero todas, después las que van 2 o más veces, después las que van 3. Si la primera tarjeta de una pasada es la última que ya se agregó, la pasada se rota para que no salga dos veces seguidas; con una sola tarjeta disponible la repetición es inevitable.

El sort es estable y las tarjetas llegan ordenadas por `position`, así que entre empates —por ejemplo, un set nuevo donde ninguna tiene historial— se conserva el orden de creación.

Ejemplo con tres tarjetas practicadas:

| Tarjeta | Mastery | Veces |
|---|---:|---:|
| A | `0.00` | 3 |
| B | `0.40` | 2 |
| C | `0.90` | 1 |

```text
Ronda: A, B, C, A, B, A   (6 tarjetas)
```

Por último, `maxRoundCards` (default `30`) trunca la ronda desde el final, que es donde están las repeticiones menos urgentes. Con la regla de las tarjetas sin historial, ese tope no puede dispararse en la primera pasada de un set.

**Integración:** `GET /flashcards/{setId}/review-round` carga las tarjetas y su mastery desde Directus y devuelve la ronda completa en una sola respuesta. La UI la estudia como cualquier set —navegación lineal, barra de progreso— y no vuelve a pedir nada entre tarjeta y tarjeta. Al terminar, la pantalla de cierre ofrece otra ronda, que se arma de nuevo con el mastery ya actualizado.

No hay cooldown ni sorteo: la ronda es una lista determinística y finita. Antes existía un `selectNext` que sorteaba una tarjeta por request con una ventana de 3 recientes; se eliminó junto con `GET /flashcards/review/next`, porque obligaba a un round trip por tarjeta y el repaso no terminaba nunca.

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
| `PATCH /flashcards/{setId}` | Renombra el set |
| `DELETE /flashcards/{setId}` | Elimina el set y recalcula el progreso de la materia |
| `POST /flashcards/{setId}/attempts` | Registra un rating idempotente y actualiza progreso por tarjeta/topic |
| `PATCH /flashcards/{setId}/attempts/{attemptId}` | Corrige el rating de un intento existente; 404 si no existe |
| `GET /quizzes?subjectId=...` | Lista los cuestionarios de la materia, con sus preguntas |
| `PATCH /quizzes/{quizId}` | Renombra el cuestionario |
| `DELETE /quizzes/{quizId}` | Elimina el cuestionario y recalcula el progreso de la materia |
| `POST /quizzes/{quizId}/attempts` | Persiste una respuesta y recalcula el progreso de la pregunta y de su topic |
| `GET /flashcards/progress?subjectId=...[&coverage=true]` | Agregados de mastery por topic. `coverage` y `total_resources` sólo con `coverage=true`, porque obligan a contar los recursos de la materia |
| `GET /flashcards/cards/{cardId}/attempts` | Consulta histórico de ratings de una tarjeta |
| `GET /flashcards/{setId}/review-round` | Devuelve la ronda de repaso completa: la lista ordenada de tarjetas, con repeticiones ya resueltas |

Los endpoints de intentos requieren un `attemptId` con formato UUID. En reintentos idempotentes se valida que la clave corresponda al intento y al contexto original.

**Las rutas que llevan el recurso en el path no reciben `subjectId`.** La materia se deriva del propio set o cuestionario con `requireOwnedSet`/`requireOwnedQuiz`, que hacen el `getById` que de todos modos hacía falta y verifican la propiedad con `requireSubjectOwnership`. Recibirla del cliente no aportaba información y abría la puerta a un par (set, materia) inconsistente; derivándola, la autorización se hace contra la materia real del recurso. Sólo la conservan los endpoints que listan por materia (`GET /flashcards`, `GET /flashcards/progress`, `GET /quizzes`), donde es el criterio de búsqueda.

---

## 10. Tests presentes

`LearningProgressPolicyTests` y `TopicProgressSnapshotTests` verifican reglas unitarias como:

- EMA después de existir mastery previo, y que una respuesta de cuestionario use exactamente el mismo EMA que un rating;
- cobertura basada en tarjetas y preguntas únicas respecto del total de recursos del topic;
- que un recurso aporte un solo intento a su topic (regresión del doble conteo);
- mayor peso de una tarjeta débil;
- cuántas veces entra una tarjeta en la ronda según su mastery;
- que un set sin historial dé una ronda igual al set, una vez cada tarjeta y en orden de creación;
- que una tarjeta sin practicar no se repita aunque el resto del set esté flojo;
- que la ronda vaya de la más floja a la más dominada sin repetir dos veces seguidas;
- que el tope de largo trunque desde el final.

`GeneratedResourcePolicyTests` cubre la validación de lo que devuelve la IA. Como tarjetas y preguntas pasan por la misma política, las reglas de topic e identidad se verifican una sola vez para ambas —id asignado por el servidor y distinto por recurso, topic obligatorio y dentro del catálogo, lote incompleto o ausente rechazado, lote excedente truncado— y sólo se prueban por separado las reglas de contenido: término y definición en las tarjetas; cantidad de opciones, opción correcta existente y textos no vacíos en las preguntas. También se verifica que las dos serialicen un único `topic_id` en snake_case y que rechacen el payload viejo con `topic_ids`.

`TopicProgressScopeTests` cuenta las requests que llegan a Directus, que es la única forma de ver el costo: verifica que calificar una tarjeta escriba **una sola** fila de `user_topic_progress` —la de su topic— y no consulte el catálogo de temas de la materia, y que leer el progreso sin `coverage=true` no liste las tarjetas ni las preguntas.

`AttemptCorrectionTests` levanta un Directus de mentira que aplica el `PATCH` sobre su estado, así el replay posterior lee la nota corregida. Verifica lo que justifica la feature —un `0` corregido a `4` deja mastery `0.70` y un solo `times_seen`, no los `0.40` de dos intentos apilados—, que sólo se manda el `rating` y no `created_at`, y que se rechaza corregir un intento de otra tarjeta o con una nota fuera de rango.

`AiRetryTests` y `AiProviderErrorMapperTests` cubren el reintento: que vuelva a intentar cuando el proveedor está saturado, que **no** reintente lo que va a fallar igual (cuota agotada, request inválido), que el backoff duplique y que espere entre intentos y no después del último.

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
- El repaso es una ronda finita que llega en una sola respuesta, ordenada por mastery y con las tarjetas flojas repetidas.
- Se puede corregir la nota de una tarjeta ya calificada dentro de la ronda: el `PATCH` edita el intento en vez de agregar otro, así el EMA no se aplica dos veces.
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
- **Attempt:** evento histórico. La API sólo permite corregir su `rating` mediante `PATCH`, pensado para el click equivocado dentro de la ronda en curso; la base de datos no impide que un administrador lo modifique o borre.
- **Mastery:** estimación normalizada entre 0 y 1. Se guarda por recurso en `user_resource_progress` y se promedia por topic en `user_topic_progress`.
- **Coverage / cobertura:** qué proporción de las flashcards y preguntas de cuestionario asociadas a un topic ya practicó.
- **Ronda de repaso:** lista finita y determinística de tarjetas de un set, ordenada de la menos a la más dominada, donde las flojas aparecen más de una vez; no hay fechas de vencimiento.

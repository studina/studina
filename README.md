# Studina: tu copiloto del aprendizaje

Studina es una plataforma de estudio que permite organizar materias y
documentos, conversar con Dina usando el contenido cargado, generar flashcards
y cuestionarios, y utilizar herramientas de planificación y Pomodoro.

**Integrantes:**

- Belén Cerrotti
- Celeste Gómez
- Mariana Juarez Goldemberg
- Miranda Marenzi

## Arquitectura

El stack base se levanta con `compose.yaml`, ubicado en la raíz. El archivo
opcional `compose.ollama.yaml` agrega Ollama en la misma red de Docker.

```text
Navegador
   │
   ▼
Frontend React/Vite :5173
   │  proxy /api
   ▼
API Spring Boot :9000 ─────► Gemini / OpenAI / Ollama (chat)
   │              │
   │              └────────► PostgreSQL + pgvector :5433
   ▼
Directus :8055
   │
   ▼
PostgreSQL :5434
```

Los servicios son:

- `frontend`: interfaz React servida por Vite.
- `dina-api`: API Spring Boot, autenticación, integración con Gemini y lógica
  RAG. El proveedor de chat puede ser Gemini, OpenAI u Ollama.
- `directus`: CMS y almacenamiento de usuarios, materias, archivos, chats,
  flashcards y cuestionarios.
- `directus-schema`: contenedor de inicialización que ejecuta la migración
  aditiva e idempotente de `scripts/directus-schema-migration.sh` y termina.
- `postgres`: base principal utilizada por Directus.
- `pgvector`: base vectorial utilizada para indexar y consultar documentos.

La única imagen de Directus está fijada en la versión `12.4.1`. El migrador usa
Alpine y la API de esa instancia, por lo que no introduce una segunda versión
de Directus. El esquema de la aplicación se define en
`scripts/directus-schema-migration.sh` y se aplica mediante el servicio
`directus-schema` al iniciar el stack.

## Requisitos previos

Para levantar el stack completo:

- Docker Engine o Docker Desktop.
- Docker Compose v2, disponible mediante `docker compose`.
- `make` para utilizar los comandos abreviados del proyecto.

Para ejecutar aplicaciones directamente en el host también se necesita:

- Node.js 22 y npm para el frontend.
- OpenJDK 21 para la API.
- LibreOffice Writer para convertir las vistas previas de DOCX a PDF.
- Google Cloud CLI únicamente si se habilita Model Armor.
- Ollama, opcional para modelos locales. Se puede ejecutar
  [con Docker Compose](#ollama-con-docker-compose) o
  [instalar en Linux](#probar-ollama-localmente-en-linux-con-docker).

### Instalar OpenJDK 21

En distribuciones basadas en Debian o Ubuntu:

```bash
sudo apt update
sudo apt install openjdk-21-jdk
java -version
```

No es necesario instalar Java en el host cuando se utiliza exclusivamente
Docker. Tampoco es necesario instalar LibreOffice en el host: la imagen de la
API ya lo incluye.

## Variables de entorno

Los archivos reales están ignorados por Git. Se crean desde los ejemplos con
los siguientes comandos, ejecutados desde la raíz del repositorio:

```bash
cp environment/frontend.env.example environment/frontend.env
cp environment/dinaapi.env.example environment/dinaapi.env
cp environment/directus.env.example environment/directus.env
cp environment/postgres.env.example environment/postgres.env
cp environment/pgvector.env.example environment/pgvector.env
```

Cada archivo tiene una responsabilidad diferente:

| Archivo | Contenido principal |
| --- | --- |
| `frontend.env` | Destino interno del proxy `/api` de Vite. |
| `dinaapi.env` | Proveedores IA, cifrado de credenciales, embeddings, RAG, Directus y Model Armor. |
| `directus.env` | Secreto, PostgreSQL y administrador inicial de Directus. |
| `postgres.env` | Base, usuario y contraseña de la base principal. |
| `pgvector.env` | Base, usuario y contraseña de la base vectorial. |

### Configuración obligatoria

1. Crear una API key de Gemini en Google AI Studio.
2. Reemplazar `GEMINI_API_KEY` en `environment/dinaapi.env`.
3. Generar un secreto para Directus:

   ```bash
   openssl rand -hex 32
   ```

4. Copiar el resultado en `SECRET` dentro de
   `environment/directus.env`.

Los valores de ejemplo de usuarios y contraseñas sirven para desarrollo local.
Para un entorno compartido o productivo deben reemplazarse.

### Variables relacionadas entre sí

- `DB_DATABASE`, `DB_USER` y `DB_PASSWORD` de `directus.env` deben coincidir
  con `POSTGRES_DB`, `POSTGRES_USER` y `POSTGRES_PASSWORD` de `postgres.env`.
- `RAG_DB_USERNAME` y `RAG_DB_PASSWORD` de `dinaapi.env` deben coincidir con
  `POSTGRES_USER` y `POSTGRES_PASSWORD` de `pgvector.env`.
- `DIRECTUS_EMAIL` y `DIRECTUS_PASSWORD` de `dinaapi.env` deben coincidir con
  `ADMIN_EMAIL` y `ADMIN_PASSWORD` de `directus.env`.

### URLs internas de Docker

Los ejemplos están preparados para Docker Compose y por eso usan nombres de
servicio:

- `jdbc:postgresql://pgvector:5432/studina_rag`
- `http://directus:8055`
- `http://dina-api:9000`

Dentro de un contenedor, `localhost` apunta al mismo contenedor y no a otro
servicio. Por ese motivo estas URLs no deben cambiarse al levantar todo con
Compose. Los puertos `5433`, `5434`, `8055` y `9000` son los publicados para
acceder desde la computadora anfitriona.

Nunca se deben subir al repositorio los archivos `*.env` ni una API key real.

### Proveedor del modelo de chat

El backend permite elegir el proveedor de chat sin modificar el código:

```env
AI_CHAT_PROVIDER=google-genai
AI_CHAT_MODEL=gemini-2.5-flash
AI_GENERATION_MODEL=gemini-2.5-flash
AI_CLASSIFICATION_MODEL=gemini-2.5-flash
AI_SUMMARY_MODEL=gemini-2.5-flash
AI_CHAT_TEMPERATURE=0.2
```

`AI_CHAT_MODEL` es el valor general. Los otros tres valores permiten usar un
modelo distinto para generar flashcards/cuestionarios, clasificar documentos y
resumir conversaciones. Si no se configuran, esas tareas usan automáticamente
`AI_CHAT_MODEL`.

Los proveedores disponibles son:

- `google-genai`: usa Gemini y es el valor por defecto.
- `ollama`: usa una instancia local de Ollama.
- `openai`: usa OpenAI o un endpoint compatible configurado por el servidor.

Para utilizar Ollama hay que ejecutar su servidor y descargar al menos un
modelo. Hay dos alternativas:

- [Docker Compose](#ollama-con-docker-compose): el archivo adicional levanta
  Ollama y conecta el backend a `http://ollama:11434`, sin instalarlo en el host.
- [Ollama instalado en Linux](#probar-ollama-localmente-en-linux-con-docker): el
  Compose base conecta el backend a `http://host.docker.internal:11434`.

Seleccionar Ollama en el perfil no descarga modelos ni comprueba la conexión.
Hay que verificar el servidor y descargar el nombre exacto antes de conversar.

Cuando la API se ejecuta directamente en el host, fuera de Docker,
`OLLAMA_BASE_URL` debe ser `http://localhost:11434`. Los embeddings continúan
usando Gemini aunque el chat use Ollama, para no invalidar los documentos ya
indexados en pgvector.

### Proveedor y credenciales por usuario

Cada usuario autenticado puede entrar en **Mi perfil**, elegir Gemini, Ollama u
OpenAI y configurar modelos distintos para chat, generación, clasificación y
resúmenes. Si no guarda una configuración personal, se utiliza la configuración
general definida por las variables anteriores.

Para permitir que los usuarios guarden sus propias API keys, generar una clave
de cifrado del servidor:

```bash
openssl rand -base64 32
```

Copiar el resultado en `environment/dinaapi.env`:

```env
AI_CREDENTIALS_ENCRYPTION_KEY=resultado_generado
```

La API key del usuario se manda una sola vez al backend, se cifra con AES-256-GCM
antes de almacenarse en Directus y nunca se devuelve al navegador. Ollama no
solicita una API key. La URL de Ollama y `OPENAI_BASE_URL` sólo pueden definirlas
los responsables del servidor; no se aceptan URLs arbitrarias enviadas desde el
frontend. En un despliegue real, el frontend y la API deben publicarse detrás de
HTTPS para proteger la credencial durante el envío.

## Gemini y embeddings

Las variables principales están en `environment/dinaapi.env`:

- `GEMINI_API_KEY`: clave privada y obligatoria.
- `GEMINI_MODEL`: valor compatible con entornos anteriores; se usa como fallback
  si no se configura `AI_CHAT_MODEL`.
- `GEMINI_TEMPERATURE`: fallback de `AI_CHAT_TEMPERATURE` para entornos anteriores.
- `GEMINI_EMBEDDING_MODEL`: modelo utilizado para embeddings.
- `GEMINI_EMBEDDING_DIMENSIONS`: dimensión configurada en pgvector.

Si se cambia la dimensión de los embeddings después de haber indexado
documentos, puede ser necesario recrear la base vectorial e indexarlos otra vez.

## Google Cloud y Model Armor

Model Armor es opcional y viene deshabilitado:

```env
MODEL_ARMOR_ENABLED=false
```

En este modo no hace falta instalar `gcloud` ni configurar credenciales de
Google Cloud.

Para habilitarlo:

1. Instalar el
   [SDK de Google Cloud](https://docs.cloud.google.com/sdk/docs/install-sdk).
2. Crear o seleccionar un proyecto de Google Cloud.
3. Habilitar Model Armor y crear un template.
4. Completar `MODEL_ARMOR_PROJECT_ID`, `MODEL_ARMOR_LOCATION` y
   `MODEL_ARMOR_TEMPLATE_ID`.
5. Cambiar `MODEL_ARMOR_ENABLED=true`.
6. Proporcionar Application Default Credentials al proceso de la API. Para
   ejecutar la API en el host se puede usar:

   ```bash
   gcloud auth application-default login
   ```

Si la API corre dentro de Docker, las credenciales también deben montarse en
el contenedor mediante un override de Compose; autenticarse solamente en el
host no las vuelve visibles automáticamente dentro del contenedor.

## Inicio rápido

Desde la raíz del repositorio:

```bash
docker compose config --quiet
make dev
```

`make dev` construye las imágenes y levanta los servicios en segundo plano.
La primera ejecución puede tardar varios minutos porque descarga las imágenes,
instala las dependencias y compila la API.

Para revisar el estado:

```bash
docker compose ps
```

Cuando todos los servicios dependientes estén saludables, estarán disponibles:

- Frontend: http://localhost:5173
- API: http://localhost:9000
- Salud de la API: http://localhost:9000/actuator/health/readiness
- Directus: http://localhost:8055
- pgvector desde el host: `localhost:5433`
- PostgreSQL de Directus desde el host: `localhost:5434`

Las credenciales para ingresar a Directus son `ADMIN_EMAIL` y
`ADMIN_PASSWORD`, definidas en `environment/directus.env`.

## Logs y diagnóstico

Ver los logs principales en tiempo real:

```bash
docker compose logs -f directus-schema directus dina-api frontend
```

Ver solamente las últimas líneas de un servicio:

```bash
docker compose logs --tail=100 dina-api
docker compose logs --tail=100 directus
```

Reconstruir y recrear un servicio después de modificarlo:

```bash
docker compose up -d --build --force-recreate dina-api
docker compose up -d --build --force-recreate frontend
```

Volver a ejecutar manualmente la migración de Directus:

```bash
docker compose run --rm directus-schema
```

## Bajar o reiniciar el proyecto

Bajar contenedores y red conservando los datos:

```bash
make stop
```

También se puede usar directamente:

```bash
docker compose down
```

Reiniciar el stack:

```bash
docker compose restart
```

Para eliminar también las bases y los archivos persistidos:

```bash
docker compose down --volumes
```

El último comando elimina datos locales y no se puede deshacer. No debe usarse
como parte de un reinicio habitual.

## Levantar servicios manualmente con Docker

Todos los comandos se ejecutan desde la raíz; ya no hay archivos Compose
separados dentro de `dinaapi` o `directus`.

### 1. Bases de datos

```bash
docker compose up -d pgvector postgres
```

### 2. Levantar Directus y aplicar la migración

```bash
docker compose up -d directus
docker compose run --rm directus-schema
```

### 3. Construir y levantar la API

```bash
docker compose up -d --build dina-api
```

### 4. Construir y levantar el frontend

```bash
docker compose up -d --build frontend
```

## Desarrollo fuera de Docker

### Backend

Primero se pueden levantar solamente las dependencias:

```bash
docker compose up -d pgvector postgres
docker compose up -d directus
docker compose run --rm directus-schema
```

La API carga opcionalmente `dinaapi/.env`. Se puede crear a partir del archivo
ya configurado para Docker:

```bash
cp environment/dinaapi.env dinaapi/.env
```

Para ejecutarla en el host se deben cambiar en `dinaapi/.env` estas URLs:

```env
RAG_DB_URL=jdbc:postgresql://localhost:5433/studina_rag
DIRECTUS_URL=http://localhost:8055
```

Luego:

```bash
cd dinaapi
chmod +x ./gradlew
./gradlew bootRun
```

### Frontend

Con la API disponible en el puerto `9000`:

```bash
cd frontend
npm ci
VITE_API_URL=http://localhost:9000 npm run dev
```

## Validaciones del proyecto

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm test
npm run build
```

Backend, con pgvector disponible y `dinaapi/.env` configurado para el host:

```bash
cd dinaapi
./gradlew check
```

`check` ejecuta los tests y verifica que la cobertura de líneas del backend no
baje del 35 %. El informe navegable se genera en
`dinaapi/build/reports/jacoco/test/html/index.html`. La suite cubre lógica de
dominio, endpoints HTTP y contratos con un Directus simulado; no llama a Gemini
ni necesita credenciales reales.

Validar Compose y la migración:

```bash
docker compose config --quiet
docker compose run --rm directus-schema
```

### Integración continua en GitHub

El workflow `.github/workflows/ci.yml` se ejecuta automáticamente con cada
`push` a cualquier rama y con cada pull request. También se puede iniciar a
mano desde la pestaña **Actions** de GitHub.

Publica dos controles independientes:

- `Frontend - lint, tests and build`: instala con `npm ci`, ejecuta ESLint, los
  tests de Vitest y el build de producción.
- `Backend - tests and coverage`: levanta un pgvector temporal, ejecuta la suite
  de Gradle con Java 21, exige al menos 35 % de cobertura de líneas y adjunta
  los reportes de tests y JaCoCo al resultado del workflow durante 14 días.

El workflow usa valores ficticios para Gemini y Directus; no necesita una API
key real ni consume cuota de Gemini.

## Problemas frecuentes

### El puerto 5433 o 5434 ya está ocupado

Listar los contenedores que publican puertos:

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

Se puede detener el proceso o contenedor que usa el puerto. Como alternativa,
se puede cambiar solamente el número ubicado a la izquierda en `compose.yaml`:

```yaml
ports:
  - "otro_puerto:5432"
```

Los servicios internos deben seguir utilizando el puerto `5432` y el nombre
del servicio correspondiente.

### `directus-schema` aparece como `Exited (0)`

Es el comportamiento esperado. Es un trabajo de inicialización de una sola
ejecución; Directus comienza después de que termina correctamente.

### La API no queda saludable

Revisar primero:

```bash
docker compose ps
docker compose logs --tail=200 dina-api
```

Las causas más comunes son una API key inválida, una URL interna cambiada a
`localhost`, credenciales inconsistentes o una base de datos que todavía no
está saludable.

### Se modificó un `.env` pero el contenedor mantiene el valor anterior

Los contenedores deben recrearse para volver a leer sus variables:

```bash
docker compose up -d --force-recreate nombre-del-servicio
```

## Cómo funciona la inteligencia artificial en Studina

### La versión corta

Studina no contiene un modelo de inteligencia artificial propio. La aplicación
prepara una consulta y se la entrega a un **proveedor**. Ese proveedor ejecuta un
**modelo** y devuelve una respuesta.

```text
Usuario
  ↓
Frontend de Studina
  ↓
Backend dina-api
  ↓
Spring AI elige el conector correcto
  ├── Google Gemini (en Internet)
  ├── OpenAI o API compatible (en Internet)
  └── Ollama (en nuestra computadora o servidor)
  ↓
Respuesta mostrada por Dina
```

En palabras simples:

- **Proveedor:** la empresa o programa que permite ejecutar modelos. Por
  ejemplo, Google, OpenAI u Ollama.
- **Modelo:** el “cerebro” concreto que procesa el pedido. Por ejemplo,
  `gemini-2.5-flash`, `gpt-4.1-mini` o `llama3.2`.
- **API key:** una credencial secreta que autoriza a usar un proveedor remoto.
  No es lo mismo que una suscripción de ChatGPT. Ollama local no necesita una.
- **Prompt:** las instrucciones y el texto que Studina le entrega al modelo.
- **Token:** un pedacito de texto. Los modelos leen y producen tokens; los
  proveedores remotos normalmente cobran o limitan el uso según esa cantidad.
- **Contexto:** la cantidad de información que el modelo puede considerar en
  una consulta. No equivale a memoria permanente.

Un modelo de lenguaje genera la respuesta prediciendo qué token debería venir a
continuación. No consulta automáticamente una fuente confiable y puede
equivocarse o inventar datos. Studina reduce ese problema agregando instrucciones
y, cuando corresponde, fragmentos de los documentos del usuario.

### Qué es Spring AI

[Spring AI](https://docs.spring.io/spring-ai/reference/) es una biblioteca para
aplicaciones Java basadas en Spring. **No es un modelo y no genera respuestas por
sí misma.** Funciona como un adaptador común entre Studina y los distintos
proveedores.

Sin esa capa, el backend tendría código diferente por todos lados para llamar a
Gemini, OpenAI y Ollama. Con Spring AI, Studina arma el pedido mediante una
interfaz común (`ChatClient`) y la implementación correspondiente lo traduce al
formato que entiende cada proveedor.

En este proyecto la abstracción propia de LLM agrega otra capa pequeña sobre
Spring AI:

1. Recibe la tarea que quiere realizar Studina.
2. Busca la configuración personal del usuario; si no existe, usa la general del
   servidor.
3. Elige proveedor, modelo y credencial.
4. Crea el cliente de Spring AI correspondiente.
5. Envía el prompt y devuelve tanto el texto como el proveedor y modelo realmente
   utilizados.

Por eso el chat puede mostrar una insignia como **“Estás usando Ollama ·
llama3.2”** y cada respuesta conserva esa información. No se confía únicamente
en lo que el modelo diga sobre sí mismo.

### Por qué hay cuatro selectores de modelos

Desde **Mi perfil** se puede elegir un modelo diferente para cada tipo de trabajo:

| Selector | Para qué se usa |
| --- | --- |
| Modelo para conversar | Respuestas normales del chat de Dina. |
| Flashcards y quizzes | Generación de tarjetas y cuestionarios a partir de los documentos. |
| Clasificar documentos | Decidir cómo categorizar el contenido cargado. |
| Resumir conversaciones | Compactar conversaciones largas para conservar contexto útil. |

Separarlos permite, por ejemplo, usar un modelo rápido y económico para
clasificar, y reservar uno más potente para generar contenido. Si se selecciona
**Usar configuración general**, vuelven a aplicarse los valores definidos por el
servidor en `environment/dinaapi.env`.

El catálogo del frontend contiene modelos conocidos y compatibles con cada
proveedor. En Ollama, además de seleccionarlo en la pantalla, el modelo debe
estar descargado en la máquina que ejecuta Ollama. El texto elegido debe coincidir
con su nombre o etiqueta, por ejemplo `llama3.2`.

### Documentos, embeddings y RAG

Un chat común no puede “ver” todos los documentos guardados. Para encontrar qué
partes son relevantes, Studina usa este proceso:

```text
Subir documento
  → extraer el texto
  → dividirlo en fragmentos
  → convertir cada fragmento en un embedding
  → guardar los vectores en PostgreSQL + pgvector

Hacer una pregunta
  → convertir la pregunta en un embedding
  → buscar los fragmentos más parecidos
  → adjuntarlos al prompt
  → pedir la respuesta al modelo de chat seleccionado
```

Un **embedding** es una lista de números que representa el significado aproximado
de un texto. **pgvector** permite comparar esas listas para encontrar contenido
parecido. Agregar al prompt los fragmentos encontrados se conoce como **RAG**
(*Retrieval-Augmented Generation* o generación aumentada con recuperación).

Importante: actualmente los embeddings de Studina se generan con Gemini, incluso
si el usuario elige Ollama para conversar. Esto mantiene compatible el índice ya
existente, pero significa que el flujo de documentos todavía no es 100 % local y
que `GEMINI_API_KEY` sigue siendo necesaria. El chat sin documentos sí puede
responder mediante Ollama.

### Diferencias prácticas entre los proveedores

| Proveedor | Dónde se ejecuta el modelo | ¿API key personal? | Consideraciones |
| --- | --- | --- | --- |
| Google Gemini | Servidores de Google | Sí | Puede tener cuota gratuita según la cuenta y la región. |
| OpenAI / compatible | Servidor remoto configurado | Sí | La API se factura aparte de ChatGPT; una cuenta con saldo `$0` no puede responder. |
| Ollama | PC o servidor administrado por Studina | No | Consume RAM, CPU/GPU y disco de esa máquina; el modelo debe estar descargado. |

---

## Ollama con Docker Compose

Esta opción ejecuta Ollama dentro de Docker; no requiere instalar el comando
`ollama` ni configurar `systemd` en la computadora. Usa la imagen oficial
`ollama/ollama:0.40.0`, CPU y un volumen persistente para los modelos.

```text
Navegador → frontend → dina-api → ollama:11434 → modelo local
```

### Qué debe hacer quien instala Studina y qué hace el estudiante

- **Responsable de la instalación:** prepara Docker y los archivos `.env`,
  levanta los servicios y descarga los modelos con los comandos siguientes.
  Docker descarga la imagen de Ollama y ejecuta su servidor automáticamente;
  la descarga del modelo es un paso separado, realizado una vez por instalación.
- **Estudiante en una instalación compartida:** abre Studina en el navegador,
  inicia sesión y selecciona Ollama y un modelo disponible en **Mi perfil**.
  No necesita instalar Ollama, descargar el modelo en su computadora, usar una
  terminal ni modificar código. Si el responsable deja Ollama como proveedor
  general, tampoco necesita guardar una configuración personal.
- **Estudiante que ejecuta todo en su propia PC:** realiza también la preparación
  inicial con Docker y terminal, o pide ayuda al responsable. Configura los
  archivos `.env`; no tiene que modificar el código de la aplicación.

Los modelos se guardan en el volumen del servidor Ollama y se comparten entre
los usuarios de esa instalación. Elegir otro modelo en el perfil no lo descarga:
quien administra el servidor debe instalarlo primero. Después de la primera
descarga, las respuestas del chat se procesan en ese servidor; los embeddings
de documentos siguen requiriendo Gemini.

### 1. Preparar y levantar Ollama

Crear los archivos de entorno como indica [Variables de entorno](#variables-de-entorno).
Desde la raíz del repositorio:

```bash
make dev-ollama
```

Este comando levanta Ollama, espera a que responda, descarga `llama3.2` y
construye y levanta Studina con la conexión interna al servidor. Si el modelo
ya está descargado, verifica su disponibilidad sin volver a bajar los mismos
archivos. Docker descarga automáticamente la imagen del servidor cuando falta.
No instala Ollama en el host.

También hay comandos para realizar cada paso por separado:

| Comando | Acción |
| --- | --- |
| `make ollama-up` | Levantar sólo el servidor Ollama y esperar su disponibilidad. |
| `make ollama-pull` | Levantar el servidor y descargar el modelo. |
| `make ollama-test` | Enviar una consulta de prueba y volver a la terminal, usando el modelo preparado. |
| `make dev-ollama` | Preparar Ollama y levantar todo Studina. |
| `make stop-ollama` | Bajar Studina y Ollama conservando bases, archivos y modelos. |

Para elegir una etiqueta distinta se puede usar
`make dev-ollama OLLAMA_MODEL=llama3.2:1b` y
`make ollama-test OLLAMA_MODEL=llama3.2:1b`. Esta variable controla la descarga y
la prueba; el modelo de Studina se elige en el perfil o en los valores generales
del backend, como se explica más abajo.

Los comandos equivalentes sin Makefile son:

```bash
docker compose -f compose.yaml -f compose.ollama.yaml config --quiet
docker compose -f compose.yaml -f compose.ollama.yaml up -d --wait ollama
```

El servidor no publica el puerto `11434` en el host. El backend accede por la red
interna de Compose y el archivo adicional fija `OLLAMA_BASE_URL=http://ollama:11434`.
El estado saludable confirma que el servidor responde; todavía hay que descargar
un modelo.

### 2. Descargar y probar un modelo

```bash
docker compose -f compose.yaml -f compose.ollama.yaml exec ollama ollama pull llama3.2
docker compose -f compose.yaml -f compose.ollama.yaml exec ollama ollama list
docker compose -f compose.yaml -f compose.ollama.yaml exec ollama \
  ollama run llama3.2 "Respondé en una oración: ¿qué es una API?"
```

La descarga de `llama3.2` ocupa aproximadamente 2 GB; además necesita RAM para
ejecutarse. Para una prueba más liviana se puede descargar `llama3.2:1b`, de
aproximadamente 1,3 GB. El modelo configurado debe coincidir con el descargado:
`llama3.2:1b` y `llama3.2` son etiquetas diferentes. La interfaz incluye
`llama3.2`; para usar otra etiqueta, elegir **Otro modelo compatible…** y
escribir su identificador exacto, por ejemplo `llama3.2:1b`. No hace falta
modificar código ni llamar a la API manualmente.

### 3. Levantar Studina con la conexión interna

```bash
docker compose -f compose.yaml -f compose.ollama.yaml up -d --build
docker compose -f compose.yaml -f compose.ollama.yaml exec -T dina-api \
  curl --fail --silent http://ollama:11434/api/tags
```

Luego entrar en **Mi perfil**, elegir **Ollama local** y **Llama 3.2**, guardar y
enviar un mensaje en Dina sin documentos. La respuesta debe mostrar proveedor
`ollama` y modelo `llama3.2`.

El archivo adicional configura la conexión, pero conserva el proveedor general
del backend. Esto permite usar Gemini u OpenAI y habilitar Ollama sólo para los
usuarios que lo seleccionen en su perfil. Para usar Ollama como configuración
general, cambiar **los cuatro modelos**, además del proveedor, en
`environment/dinaapi.env`:

```env
AI_CHAT_PROVIDER=ollama
AI_CHAT_MODEL=llama3.2
AI_GENERATION_MODEL=llama3.2
AI_CLASSIFICATION_MODEL=llama3.2
AI_SUMMARY_MODEL=llama3.2
```

Después recrear la API con ambos archivos:

```bash
docker compose -f compose.yaml -f compose.ollama.yaml up -d --force-recreate dina-api
```

Cambiar sólo `AI_CHAT_PROVIDER` deja los modelos Gemini explícitos del ejemplo,
que Ollama no puede ejecutar. Una configuración personal previamente guardada
tiene prioridad sobre los valores generales; usar **Usar configuración general**
en el perfil para probar estos últimos.

Los embeddings siguen utilizando Gemini. Esta opción dockeriza el servidor de
chat; para indexar documentos y consultar su contenido sigue haciendo falta una
`GEMINI_API_KEY` válida.

### 4. Diagnóstico y persistencia

```bash
docker compose -f compose.yaml -f compose.ollama.yaml ps
docker compose -f compose.yaml -f compose.ollama.yaml logs --tail=100 ollama dina-api
docker compose -f compose.yaml -f compose.ollama.yaml exec ollama ollama ps
docker compose -f compose.yaml -f compose.ollama.yaml down
```

Usar ambos archivos también al recrear servicios: `make dev` utiliza únicamente
el Compose base y volvería a conectar la API al Ollama del host. `down` conserva
los modelos en `ollama_data`; `down --volumes` elimina tanto los modelos como los
datos del resto de Studina. No se necesita detener un Ollama instalado en el
host porque esta opción no publica su puerto.

La configuración funciona con CPU. Para NVIDIA o AMD hace falta habilitar los
dispositivos y preparar el runtime apropiado; seguir la
[guía oficial de Docker de Ollama](https://docs.ollama.com/docker).

La API permite hasta 180 segundos para recibir una respuesta de Ollama y 5
segundos para establecer la conexión. Los errores no se reintentan
automáticamente: un servidor detenido o un modelo inexistente devuelve un
error para que el usuario pueda corregir la configuración y volver a intentar.
Esto se aplica tanto al proveedor general como a las preferencias personales.

### 5. Prueba automatizada con inferencia real

`OllamaLlmIntegrationTests` comprueba el adaptador de Studina con un servidor real:
historial de mensajes, selección personal del proveedor y modelo para generación,
texto recibido y metadatos. No usa Directus, pgvector ni API keys. Se omite en la
suite habitual y en CI si no se define `OLLAMA_TEST_URL`.

Ambos ejemplos se ejecutan desde la raíz del repositorio. Con Ollama instalado
en el host y un modelo descargado:

```bash
(cd dinaapi && \
  OLLAMA_TEST_URL=http://127.0.0.1:11434 \
  OLLAMA_TEST_MODEL=llama3.2 \
  ./gradlew test --tests '*OllamaLlmIntegrationTests' --rerun-tasks --no-daemon)
```

Para probar exclusivamente Docker sin publicar el servicio habitual, crear una
instancia de prueba accesible sólo desde el host:

```bash
docker run -d --rm --name studina-ollama-test \
  -p 127.0.0.1:11435:11434 \
  -v studina_ollama_test:/root/.ollama ollama/ollama:0.40.0
```

Esperar a que este comando responda sin errores antes de descargar el modelo:

```bash
docker exec studina-ollama-test ollama list
docker exec studina-ollama-test ollama pull llama3.2:1b
(cd dinaapi && \
  OLLAMA_TEST_URL=http://127.0.0.1:11435 \
  OLLAMA_TEST_MODEL=llama3.2:1b \
  ./gradlew test --tests '*OllamaLlmIntegrationTests' --rerun-tasks --no-daemon)
docker stop studina-ollama-test
```

El volumen de prueba se conserva para evitar otra descarga. `--rerun-tasks`
impide que Gradle reutilice un resultado anterior con los tests omitidos. Sólo
se aceptan URLs HTTP de loopback y las respuestas pueden tardar hasta tres
minutos por test con CPU. Esta prueba no sustituye la comprobación del flujo
completo desde el navegador ni la de RAG con documentos.

### 6. Migrar desde Ollama instalado en Linux

Estos pasos corresponden a una instalación con binario en
`/usr/local/bin/ollama`, bibliotecas en `/usr/local/lib/ollama` y servicio
`/etc/systemd/system/ollama.service`. Comprobar la ruta con `command -v ollama`;
si difiere, usar la ruta de esa instalación. La
[guía oficial de desinstalación](https://docs.ollama.com/linux#uninstall)
explica las ubicaciones alternativas.

Detener el servicio, deshabilitar su inicio y quitar el programa:

```bash
sudo systemctl disable --now ollama
sudo rm -f /etc/systemd/system/ollama.service
sudo rm -rf /etc/systemd/system/ollama.service.d
sudo rm -f /usr/local/bin/ollama
sudo rm -rf /usr/local/lib/ollama
sudo systemctl daemon-reload
```

Esto conserva los modelos descargados en el host. Desde la raíz de Studina,
levantar y probar la instalación Docker:

```bash
make dev-ollama
make ollama-test
```

El contenedor tiene su propio volumen: descarga el modelo allí y no reutiliza
automáticamente `/usr/share/ollama`. Después elegir Ollama y Llama 3.2 en
**Mi perfil**, guardar y probar un chat sin documentos. Para bajar la instalación
sin perder los datos, usar `make stop-ollama`.

Una vez comprobado Docker, si también se quieren eliminar los modelos del host
y la cuenta del servicio, ejecutar:

```bash
sudo userdel ollama
sudo groupdel ollama
sudo rm -rf /usr/share/ollama
```

El último comando elimina las descargas anteriores del host. No afecta al
volumen Docker ni a las bases o los archivos de Studina. El grupo puede haber
sido eliminado automáticamente al borrar el usuario; en ese caso `groupdel`
informará que ya no existe.

## Probar Ollama localmente en Linux con Docker

Esta guía describe el caso usado por el equipo: **Ollama corre en Linux y
Studina corre con Docker Compose**.

La conexión queda así:

```text
Navegador → frontend → dina-api (contenedor Docker)
                             ↓
               host.docker.internal:11434
                             ↓
                    Ollama en la PC
                             ↓
                       llama3.2
```

### 1. Instalar Ollama

La instalación oficial para Linux es:

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Comprobar que el comando quedó instalado:

```bash
ollama --version
```

En distribuciones que usan `systemd`, comprobar también el servicio:

```bash
sudo systemctl enable --now ollama
sudo systemctl status ollama --no-pager
```

El estado esperado contiene `Active: active (running)`. Para salir de la vista
interactiva de `systemctl status`, presionar `q`.

> La página oficial también ofrece instaladores para macOS y Windows. Los pasos
> de red de esta sección están pensados específicamente para Linux + Docker.

### 2. Descargar y probar el modelo

Descargar Llama 3.2 una sola vez:

```bash
ollama pull llama3.2
```

Ver los modelos descargados:

```bash
ollama list
```

Probarlo sin Studina:

```bash
ollama run llama3.2
```

Escribir, por ejemplo, `Respondeme en español: ¿qué es una API?`. Si contesta,
el modelo y Ollama funcionan. Para cerrar el chat usar `/bye` o `Ctrl+D`.

Los modelos ocupan varios gigabytes. La primera respuesta puede tardar más porque
Ollama tiene que cargar los pesos del modelo en RAM y, si está disponible, VRAM.

### 3. Permitir que Docker llegue a Ollama

Este es el paso fácil de pasar por alto. Ollama escucha de forma predeterminada
solamente en `127.0.0.1:11434`. Además, dentro del contenedor de `dina-api`,
`localhost` significa “este contenedor”, no la computadora anfitriona. Por eso un
error como este no significa que el modelo esté roto:

```text
Connection refused: localhost/127.0.0.1:11434
```

Crear una configuración adicional para el servicio de Ollama:

```bash
sudo mkdir -p /etc/systemd/system/ollama.service.d
printf '[Service]\nEnvironment="OLLAMA_HOST=0.0.0.0:11434"\n' \
  | sudo tee /etc/systemd/system/ollama.service.d/override.conf
```

Aplicarla y reiniciar Ollama:

```bash
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

Verificar que la variable fue tomada:

```bash
systemctl show ollama --property=Environment --no-pager
```

La salida debe incluir:

```text
OLLAMA_HOST=0.0.0.0:11434
```

También se puede confirmar que el puerto está escuchando:

```bash
ss -ltn 'sport = :11434'
```

> **Seguridad:** `0.0.0.0` hace que Ollama escuche en las interfaces de red de la
> máquina. Usarlo sólo en una red local confiable o con firewall. No publicar el
> puerto `11434` directamente en Internet. En producción conviene limitar por red
> qué backend puede acceder al servidor de Ollama.

Si `sudo systemctl edit ollama` muestra
`temporary file is empty` o resulta incómodo guardar desde el editor, el comando
con `tee` de arriba crea el archivo directamente y evita ese problema.

### 4. Verificar la API desde la computadora

Ollama expone una API HTTP local. Este endpoint lista los modelos disponibles:

```bash
curl --fail http://localhost:11434/api/tags
```

La respuesta debe ser JSON y contener un nombre como `llama3.2:latest`. Si este
comando falla, todavía hay que resolver Ollama antes de revisar Studina.

### 5. Levantar Studina y verificar la conexión desde Docker

Desde la raíz del repositorio:

```bash
make dev
```

Si Studina ya estaba levantada antes de configurar Ollama, recrear el backend:

```bash
docker compose up -d --force-recreate dina-api
```

`docker compose restart` no alcanza cuando cambió una variable de entorno o la
configuración de Compose: reinicia el mismo contenedor con su configuración
anterior. `--force-recreate` crea uno nuevo con los valores actuales.

Ahora probar desde el mismo contenedor que hará la consulta:

```bash
docker compose exec -T dina-api sh -lc \
  'curl --fail --silent http://host.docker.internal:11434/api/tags'
```

Si aparece `llama3.2:latest`, toda la ruta de red está funcionando. El
`compose.yaml` ya configura automáticamente para `dina-api`:

```text
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

Por eso, para el desarrollo normal con Docker, no hace falta modificar
`environment/dinaapi.env`. Si se ejecuta el backend directamente con
`./gradlew bootRun`, fuera de Docker, usar
`OLLAMA_BASE_URL=http://localhost:11434`.

### 6. Elegir Ollama desde el frontend

1. Abrir [http://localhost:5173](http://localhost:5173) e iniciar sesión.
2. Entrar en el menú del usuario y abrir **Mi perfil**.
3. En **Proveedor de inteligencia artificial**, elegir **Ollama local**.
4. Elegir **Llama 3.2** en “Modelo para conversar” y en los selectores de las
   otras tareas que se quieran probar.
5. Presionar **Guardar configuración**.
6. Abrir Dina, empezar un chat sin documentos y enviar `Hola, respondeme en
   español`.
7. Comprobar que la cabecera y la respuesta muestran **Ollama · llama3.2**.

No hay que ingresar una API key para Ollama. Si la pantalla parece conservar una
versión vieja, hacer una recarga completa con `Ctrl+Shift+R`.

### 7. Ver si el modelo está trabajando

En otra terminal:

```bash
watch -n 1 ollama ps
```

Una salida como esta confirma que el modelo está cargado:

```text
NAME               SIZE      PROCESSOR          CONTEXT    UNTIL
llama3.2:latest    2.9 GB    14%/86% CPU/GPU    4096       4 minutes from now
```

- `PROCESSOR` muestra si trabaja con CPU, GPU o ambas.
- `CONTEXT` indica el tamaño de contexto cargado.
- `UNTIL` indica cuándo se descargará de memoria si no recibe nuevas consultas.

Que `ollama ps` esté vacío no es necesariamente un error: puede significar que
ningún modelo está atendiendo una consulta o que ya se descargó de memoria por
inactividad. `ollama list`, en cambio, muestra los modelos instalados en disco.

Para mirar el backend mientras se hace la prueba:

```bash
docker compose logs -f dina-api
```

Salir del seguimiento de logs con `Ctrl+C`; eso no apaga los contenedores.

### 8. Problemas frecuentes

| Síntoma | Causa probable | Solución |
| --- | --- | --- |
| `Connection refused: localhost/127.0.0.1:11434` en `dina-api` | El contenedor intenta hablar consigo mismo o conserva una configuración vieja. | Ejecutar `docker compose up -d --force-recreate dina-api` y revisar que use `host.docker.internal`. |
| `curl localhost:11434/api/tags` funciona, pero el `curl` dentro del contenedor falla | Ollama sólo escucha en `127.0.0.1` o el override no fue aplicado. | Revisar `systemctl show ollama --property=Environment --no-pager`, aplicar `OLLAMA_HOST=0.0.0.0:11434` y reiniciar el servicio. |
| `sudo systemctl edit ollama` termina con “temporary file is empty” | El editor se cerró sin guardar contenido válido. | Crear el override con el comando `printf ... | sudo tee ...` de esta guía. |
| `model ... not found` | El nombre seleccionado en Studina no está descargado. | Ejecutar `ollama pull llama3.2`, verificar con `ollama list` y elegir exactamente ese modelo. |
| La primera respuesta tarda mucho | El modelo se está cargando en memoria o la PC trabaja principalmente con CPU. | Esperar la primera carga y revisar `ollama ps`. Modelos más pequeños consumen menos recursos. |
| `ollama ps` no muestra nada | No hay un modelo cargado en ese momento. | Enviar una consulta desde Studina o ejecutar `ollama run llama3.2`. |
| El perfil dice Ollama pero el chat falla | La elección se guardó, pero el backend no alcanza el servidor local. | Ejecutar las verificaciones de los pasos 4 y 5 y mirar `docker compose logs --since=2m dina-api`. |
| El chat funciona, pero una operación con documentos falla | Los embeddings aún dependen de Gemini. | Confirmar que `GEMINI_API_KEY` sea válida y que la cuota de Gemini esté disponible. |

### 9. Detener o volver a iniciar Ollama

```bash
sudo systemctl stop ollama
sudo systemctl start ollama
sudo systemctl restart ollama
```

Detener los contenedores de Studina no detiene Ollama porque es un servicio de la
computadora anfitriona:

```bash
make stop
```

### 10. Qué cambia cuando Studina está desplegada

En una instalación real **el usuario final no debería editar variables de
entorno, instalar Ollama ni tocar `systemd`**. Esas tareas corresponden a quien
administra la infraestructura:

1. Ejecuta Ollama en un servidor con recursos suficientes, instalado en el host
   o mediante `compose.ollama.yaml`.
2. Descarga los modelos habilitados por Studina.
3. Configura una conexión de red privada desde `dina-api` hacia Ollama.
4. Define `OLLAMA_BASE_URL` una sola vez en el entorno del backend.
5. Mantiene el catálogo del frontend alineado con los modelos realmente
   instalados.

Después de eso, el usuario sólo entra en **Mi perfil**, selecciona Ollama y el
modelo instalado, y guarda. La computadora del usuario no ejecuta el modelo; lo
hace el servidor. Esto también implica dimensionar CPU/GPU, RAM y concurrencia:
varias consultas simultáneas compiten por los recursos del servidor.

### Referencias oficiales

- [Instalación de Ollama en Linux](https://docs.ollama.com/linux)
- [Inicio rápido de Ollama](https://docs.ollama.com/quickstart)
- [Preguntas frecuentes y configuración de red de Ollama](https://docs.ollama.com/faq)
- [API de Ollama para listar modelos](https://docs.ollama.com/api/tags)
- [Referencia de Spring AI](https://docs.spring.io/spring-ai/reference/)
- [Chat Model API de Spring AI](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)
- [Integración de Spring AI con Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)

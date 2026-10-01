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

El proyecto se levanta desde un único `compose.yaml` ubicado en la raíz:

```text
Navegador
   │
   ▼
Frontend React/Vite :5173
   │  proxy /api
   ▼
API Spring Boot :9000 ─────► Gemini API
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
  RAG.
- `directus`: CMS y almacenamiento de usuarios, materias, archivos, chats,
  flashcards y cuestionarios.
- `directus-schema`: contenedor de inicialización que ejecuta la migración
  aditiva e idempotente de `scripts/directus-schema-migration.sh` y termina.
- `postgres`: base principal utilizada por Directus.
- `pgvector`: base vectorial utilizada para indexar y consultar documentos.

La única imagen de Directus está fijada en la versión `12.4.1`. El migrador usa
Alpine y la API de esa instancia, por lo que no introduce una segunda versión
de Directus. `directus/schema.yaml` se conserva como snapshot de referencia.

## Requisitos previos

Para levantar el stack completo:

- Docker Engine o Docker Desktop.
- Docker Compose v2, disponible mediante `docker compose`.
- `make` para utilizar los comandos abreviados del proyecto.

Para ejecutar aplicaciones directamente en el host también se necesita:

- Node.js 22 y npm para el frontend.
- OpenJDK 21 para la API.
- Google Cloud CLI únicamente si se habilita Model Armor.

### Instalar OpenJDK 21

En distribuciones basadas en Debian o Ubuntu:

```bash
sudo apt update
sudo apt install openjdk-21-jdk
java -version
```

No es necesario instalar Java en el host cuando se utiliza exclusivamente
Docker.

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
| `dinaapi.env` | Gemini, embeddings, RAG, Directus y Model Armor. |
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

## Gemini

Las variables principales están en `environment/dinaapi.env`:

- `GEMINI_API_KEY`: clave privada y obligatoria.
- `GEMINI_MODEL`: modelo utilizado para chat y generación de contenido.
- `GEMINI_TEMPERATURE`: nivel de variación de las respuestas.
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
./gradlew test
```

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
- `Backend - tests`: levanta un pgvector temporal y ejecuta la suite de Gradle
  con Java 21.

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

# Studina: tu copiloto del aprendizaje

Studina permite organizar materias y documentos, estudiar con Dina, generar
recursos de práctica y planificar sesiones de estudio.

**Integrantes:** Belén Cerrotti, Celeste Gómez, Mariana Juarez Goldemberg y
Miranda Marenzi.

## Requisitos

- Docker y Docker Compose v2.
- Una API key de Gemini, necesaria también para los embeddings.
- OpenSSL para generar las claves de la instalación.

## Instalación

Desde la raíz del repositorio, copiar los archivos de ejemplo:

```bash
cp environment/frontend.env.example environment/frontend.env
cp environment/dinaapi.env.example environment/dinaapi.env
cp environment/directus.env.example environment/directus.env
cp environment/postgres.env.example environment/postgres.env
cp environment/pgvector.env.example environment/pgvector.env
```

Completar estos valores:

- `GEMINI_API_KEY` en `environment/dinaapi.env`.
- `SECRET` en `environment/directus.env`, con el resultado de
  `openssl rand -hex 32`.
- `AI_CREDENTIALS_ENCRYPTION_KEY` en `environment/dinaapi.env`, con el resultado
  de `openssl rand -base64 32`. Se necesita para guardar las API keys personales.

Los ejemplos incluyen credenciales de desarrollo. Si se cambian, mantener
alineados los siguientes valores:

| Configuración | Debe coincidir con |
| --- | --- |
| `DB_DATABASE`, `DB_USER`, `DB_PASSWORD` de Directus | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` de `postgres.env` |
| Base de `RAG_DB_URL`, `RAG_DB_USERNAME`, `RAG_DB_PASSWORD` de la API | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` de `pgvector.env` |
| `DIRECTUS_EMAIL`, `DIRECTUS_PASSWORD` de la API | `ADMIN_EMAIL`, `ADMIN_PASSWORD` de Directus |

Los archivos de ejemplo ya usan las direcciones internas de Docker, como
`http://directus:8055`. No reemplazarlas por `localhost` al usar Compose.
Los archivos `.env` y las claves reales no se suben al repositorio.

Levantar la aplicación:

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps -a
```

La primera ejecución descarga las imágenes y compila las aplicaciones.
El servicio `directus-schema` prepara el esquema y termina con código `0`;
la API espera a que termine.

| Servicio | Dirección |
| --- | --- |
| Studina | http://localhost:5173 |
| API | http://localhost:9000 |
| Salud de la API | http://localhost:9000/actuator/health/readiness |
| Directus | http://localhost:8055 |
| PostgreSQL de Directus | `localhost:5434` |
| PostgreSQL con pgvector | `localhost:5433` |

Para ingresar a Directus, usar `ADMIN_EMAIL` y `ADMIN_PASSWORD` de
`environment/directus.env`.

## Proveedores de IA

La instalación usa Gemini por defecto. En **Mi perfil**, cada usuario puede
elegir Gemini, OpenAI u Ollama y seleccionar modelos para chat, generación,
clasificación y resúmenes. Gemini y OpenAI requieren una API key personal;
Ollama no. Sin preferencias personales, se usa la configuración del servidor.

Los valores generales se encuentran en `environment/dinaapi.env`.
`OPENAI_BASE_URL` y `OLLAMA_BASE_URL` se configuran en el servidor.
Los embeddings siguen usando Gemini, independientemente del proveedor elegido.
Cambiar su modelo o dimensión requiere revisar el índice y volver a indexar
los documentos.

### Ollama con Docker

En `environment/dinaapi.env`, usar `OLLAMA_BASE_URL=http://ollama:11434` y ejecutar:

```bash
docker compose --profile ollama up -d --build
docker compose --profile ollama logs -f ollama-pull
```

El perfil agrega Ollama y descarga `llama3.2`. Esperar a que `ollama-pull`
termine correctamente antes de seleccionar el modelo en **Mi perfil**.
El comando también sirve si el resto de la aplicación ya está levantado.

Para descargar otro modelo:

```bash
docker compose --profile ollama exec ollama ollama pull llama3.2:1b
```

Los modelos descargados aparecen en **Mi perfil** y se conservan en el volumen
`ollama_data`. Elegir uno en la aplicación no lo descarga.

Si Ollama ya está instalado en el host, no hace falta activar el perfil.
La API dentro de Docker puede conectarse con
`OLLAMA_BASE_URL=http://host.docker.internal:11434`; Ollama debe aceptar
conexiones desde la red de Docker. Para usar GPU, consultar la
[documentación de Ollama](https://docs.ollama.com/docker).

### Model Armor

Es opcional y viene deshabilitado con `MODEL_ARMOR_ENABLED=false`.
Para habilitarlo, crear un template en Google Cloud y completar
`MODEL_ARMOR_PROJECT_ID`, `MODEL_ARMOR_LOCATION` y `MODEL_ARMOR_TEMPLATE_ID`
en `environment/dinaapi.env`, además de cambiar `MODEL_ARMOR_ENABLED=true`.

La API necesita Application Default Credentials. En el host pueden obtenerse
con `gcloud auth application-default login`. En Docker deben proporcionarse
al contenedor, por ejemplo mediante un archivo Compose adicional que monte las
credenciales; iniciar sesión en el host no las comparte automáticamente.

## Desarrollo fuera de Docker

Se necesitan Java 21 y Node.js 22. Para convertir vistas previas de DOCX, la API
también necesita LibreOffice Writer; su imagen Docker ya lo incluye.

Levantar las dependencias y preparar la configuración local:

```bash
docker compose up -d directus-schema pgvector
cp environment/dinaapi.env dinaapi/.env
```

En `dinaapi/.env`, cambiar las direcciones para acceder desde el host:

```env
RAG_DB_URL=jdbc:postgresql://localhost:5433/studina_rag
DIRECTUS_URL=http://localhost:8055
```

Si Ollama está instalado en el host, configurar también
`OLLAMA_BASE_URL=http://localhost:11434`.

Ejecutar el backend:

```bash
cd dinaapi
./gradlew bootRun
```

En otra terminal, ejecutar el frontend:

```bash
cd frontend
npm ci
VITE_API_URL=http://localhost:9000 npm run dev
```

## Pruebas

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

`check` ejecuta las pruebas y exige un mínimo de 35 % de cobertura de líneas.
El reporte queda en `dinaapi/build/reports/jacoco/test/html/index.html`.
Las pruebas habituales no llaman a proveedores reales. El workflow
`.github/workflows/ci.yml` ejecuta estas validaciones en GitHub.

## Comandos útiles

Ver el estado y los logs:

```bash
docker compose ps -a
docker compose logs -f dina-api directus-schema
```

Después de cambiar un archivo de entorno, recrear el servicio afectado:

```bash
docker compose up -d --force-recreate dina-api
```

Detener la aplicación y conservar los datos:

```bash
docker compose --profile ollama down
```

Con `make` también están disponibles `make dev`, `make dev-ollama` y
`make stop`. **`make clean` elimina las bases, los archivos y los modelos
descargados**; no se usa para un reinicio habitual.

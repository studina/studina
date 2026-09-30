# Studina: tu copiloto del aprendizaje

Studina reúne un frontend React, una API Spring Boot, Directus y dos bases
PostgreSQL: una para los datos de la aplicación y otra con pgvector para RAG.

**Integrantes:**

- Belén Cerrotti
- Celeste Gómez
- Mariana Juarez Goldemberg
- Miranda Marenzi

## Requisitos

Para levantar todo el proyecto solamente se necesitan Docker, Docker Compose y
`make`. Node.js 22 y JDK 21 se usan dentro de las imágenes; solo hacen falta en
el host si se ejecuta cada aplicación fuera de Docker.

## Configuración inicial

Desde la raíz del repositorio, crear los archivos locales de configuración:

```bash
cp environment/frontend.env.example environment/frontend.env
cp environment/dinaapi.env.example environment/dinaapi.env
cp environment/directus.env.example environment/directus.env
cp environment/postgres.env.example environment/postgres.env
cp environment/pgvector.env.example environment/pgvector.env
```

Los archivos `*.env` están ignorados por Git. No se deben versionar claves ni
contraseñas reales.

Antes de iniciar:

1. En `environment/dinaapi.env`, reemplazar `GEMINI_API_KEY` por una clave de
   Google AI Studio.
2. En `environment/directus.env`, generar `SECRET` con
   `openssl rand -hex 32`.
3. Si se cambian usuarios, contraseñas o nombres de base, mantener sincronizados
   `directus.env` con `postgres.env`, y `dinaapi.env` con `pgvector.env`.

Las URLs de los ejemplos (`pgvector`, `postgres`, `directus` y `dina-api`) son
nombres internos de Docker Compose. No deben reemplazarse por `localhost` al
levantar el stack completo.

### Model Armor (opcional)

Model Armor viene deshabilitado con `MODEL_ARMOR_ENABLED=false`; no se necesita
para el entorno local. Para activarlo:

1. Habilitar Model Armor y crear un template en Google Cloud.
2. Darle Application Default Credentials al proceso que ejecuta la API. Para
   ejecutarla en el host se puede usar `gcloud auth application-default login`;
   en Docker también hay que montar esas credenciales dentro del contenedor.
3. Completar `MODEL_ARMOR_PROJECT_ID`, `MODEL_ARMOR_LOCATION` y
   `MODEL_ARMOR_TEMPLATE_ID`, y cambiar `MODEL_ARMOR_ENABLED=true`.

## Levantar el proyecto

Validar primero la configuración y luego iniciar todos los servicios:

```bash
docker compose config --quiet
make dev
```

Servicios disponibles:

- Frontend: http://localhost:5173
- API: http://localhost:9000
- Directus: http://localhost:8055
- pgvector: `localhost:5433`
- PostgreSQL de Directus: `localhost:5434`

Para consultar el estado y los logs:

```bash
docker compose ps
docker compose logs -f directus-schema directus dina-api frontend
```

Si el puerto `5433` o `5434` ya está ocupado, detener el proceso o contenedor
que lo usa antes de ejecutar `make dev`, o cambiar solamente el puerto publicado
del lado izquierdo en `compose.yaml`.

## Bajar el proyecto

```bash
make stop
```

Esto elimina los contenedores y la red, pero conserva los volúmenes con datos.
Para eliminar también los datos locales se debe ejecutar de forma explícita
`docker compose down --volumes`.

## Desarrollo y validación

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm test
npm run build
```

Backend:

```bash
cd dinaapi
./gradlew test
```

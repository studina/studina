# Studina: tu copiloto del aprendizaje

**Integrantes:**
- Belén Cerrotti
- Celeste Gómez
- Mariana Juarez Goldemberg
- Miranda Marenzi

---

## Requisitos previos

- Docker y Docker Compose
- Node.js 18+
- Openjdk 21 ([instrucciones de instalacion](#instalar-openjdk))
- Gcloud ([guía de gcloud](#gcloud-y-model-armor))

---

## Variables de entorno

Crear un archivo `.env` dentro de `/dinaapi` a partir del arhivo de ejemplo (`.env.example`) y establecer las variables con las configuraciones necesarias.

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

Para utilizar Ollama, primero hay que instalarlo y descargar un modelo:

```bash
ollama pull llama3.2
```

Después se cambia la configuración:

```env
AI_CHAT_PROVIDER=ollama
AI_CHAT_MODEL=llama3.2
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

Cuando la API se ejecuta directamente en el host, `OLLAMA_BASE_URL` debe ser
`http://localhost:11434`. Los embeddings continúan usando Gemini aunque el chat
use Ollama, para no invalidar los documentos ya indexados en pgvector.

---

## Instalar OpenJDK
Se puede instalar usando apt:
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

---

## Gcloud y Model Armor
### Instalación de gcloud
Para correr model armor se necesita instalar el sdk de [gcloud](https://docs.cloud.google.com/sdk/docs/install-sdk#deb).

### Autenticación
Luego de instalar gcloud, autenticarse mediante la documentación proporcionada [aquí](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment) para poder utilizar model armor.

### Configuración
Hay que tener un proyecto creado en gcloud, para el cual se tiene que habilitar model armor y crear un template.

Luego se toma el nombre del proyecto y se agrega al archivo `.env`, con la forma mostrada mediante `.env.example`

---

## Inicio rápido

Con `make` instalado, desde la raíz del proyecto:

```bash
make dev    # levanta todo
make stop   # baja los servicios Docker
```

> El backend queda corriendo en background. Al hacer Ctrl+C se detiene el frontend; para bajar Docker usar `make stop`.

Una vez levantado, el frontend estará disponible en [http://localhost:5173](http://localhost:5173).

---

## Levantar los servicios (manual)

### 1. Backend — dinaapi

Desde `/dinaapi`, levantar la base de datos vectorial:

```bash
docker compose up -d
```

En otra terminal, dar permisos y arrancar la API:

```bash
chmod +x ./gradlew
./gradlew bootRun
```

### 2. CMS — Directus

Desde `/directus`:

```bash
docker compose up -d
```

Disponible en [http://localhost:8055](http://localhost:8055).

### 3. Frontend

Desde `/frontend`:

```bash
npm install
npm run dev
```

Disponible en [http://localhost:5173](http://localhost:5173).

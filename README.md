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
- OpenJDK 21 — instalar con:

```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

---

## Variables de entorno

Crear un archivo `.env` dentro de `/dinaapi` con:

```env
GEMINI_API_KEY=tu_clave_api
GEMINI_MODEL=modelo_elegido
GEMINI_TEMPERATURE=0.2
```

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
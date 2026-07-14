# Studina: tu copiloto del aprendizaje

Integrantes:
- Belén Cerrotti
- Celeste Gómez
- Mariana Juarez Goldemberg
- Miranda Marenzi

## Correr el frontend

Requiere Node.js instalado.

```bash
npm install
npm run dev
```

Abre [http://localhost:5173](http://localhost:5173) en el navegador.

## Correr Directus

Levantar con

```bash
docker compose up -d
```

Abre [http://localhost:8055](http://localhost:8055) en el navegador.

## Instalar Ollama

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Descargar el modelo

```bash
ollama pull llama3.2:3b
```

Pueden probarlo con

```bash
ollama serve
```

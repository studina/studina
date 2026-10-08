.PHONY: dev stop clean ollama-up ollama-pull ollama-test dev-ollama stop-ollama

BACKEND_PID := .backend.pid
OLLAMA_COMPOSE ?= docker compose -f compose.yaml -f compose.ollama.yaml
OLLAMA_MODEL ?= llama3.2

dev:
	docker compose build
	docker compose up -d

stop:
	docker compose down

clean:
	docker compose down --volumes --remove-orphans

# Ollama en Docker es opcional; dev/stop/clean siguen usando el Compose base.
ollama-up:
	$(OLLAMA_COMPOSE) up -d --wait ollama

# Descarga el modelo al volumen persistente; permite OLLAMA_MODEL=llama3.2:1b.
ollama-pull: ollama-up
	$(OLLAMA_COMPOSE) exec -T ollama ollama pull "$(OLLAMA_MODEL)"

# Una consulta y salida automática: no deja abierta la entrada estándar.
ollama-test: ollama-up
	$(OLLAMA_COMPOSE) exec -T --interactive=false ollama ollama run "$(OLLAMA_MODEL)" "Respondé solamente OK"

# Arranca Studina con Ollama disponible; el proveedor se elige en Mi perfil.
dev-ollama: ollama-pull
	$(OLLAMA_COMPOSE) up -d --build

# Detiene Studina y Ollama sin eliminar sus modelos ni los datos de la aplicación.
stop-ollama:
	$(OLLAMA_COMPOSE) down

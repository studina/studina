.PHONY: dev dev-ollama stop clean

dev:
	docker compose up -d --build

dev-ollama:
	docker compose --profile ollama up -d --build

stop:
	docker compose --profile ollama down

# Borra también los modelos de Ollama al eliminar todos los volúmenes.
clean:
	docker compose --profile ollama down --volumes --remove-orphans

.PHONY: dev dev-ollama stop clean aws-setup aws-deploy aws-status aws-logs aws-stop aws-backup

dev:
	docker compose up -d --build

dev-ollama:
	docker compose --profile ollama up -d --build

stop:
	docker compose --profile ollama down

# Borra también los modelos de Ollama al eliminar todos los volúmenes.
clean:
	docker compose --profile ollama down --volumes --remove-orphans

# Producción en una VM: utiliza un stack independiente del entorno local.
aws-setup:
	./scripts/aws/setup-env.sh "$(DOMAIN)" "$(EMAIL)"

aws-deploy:
	./scripts/aws/deploy.sh

aws-status:
	./scripts/aws/compose.sh ps

aws-logs:
	./scripts/aws/compose.sh logs --follow --tail=100

aws-stop:
	./scripts/aws/compose.sh stop

aws-backup:
	./scripts/aws/backup.sh

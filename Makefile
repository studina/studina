.PHONY: dev stop

BACKEND_PID := .backend.pid

dev:
	docker compose build
	docker compose up -d

stop:
	docker compose down

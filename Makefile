.PHONY: dev stop clean

BACKEND_PID := .backend.pid

dev:
	docker compose build
	docker compose up -d

stop:
	docker compose down

clean:
	docker compose down --volumes --remove-orphans

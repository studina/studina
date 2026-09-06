.PHONY: dev stop

BACKEND_PID := .backend.pid

dev:
	@echo "▶  Levantando bases de datos..."
	cd dinaapi && docker compose up -d
	cd directus && docker compose up -d
	@echo "▶  Iniciando backend (en background)..."
	cd dinaapi && ./gradlew bootRun & echo $$! > ../$(BACKEND_PID)
	@echo "▶  Iniciando frontend..."
	cd frontend && npm install --silent && npm run dev

stop:
	@echo "■  Bajando servicios Docker..."
	cd dinaapi && docker compose down
	cd directus && docker compose down
	@echo "■  Deteniendo backend..."
	@if [ -f $(BACKEND_PID) ]; then \
		kill $$(cat $(BACKEND_PID)) 2>/dev/null || true; \
		rm -f $(BACKEND_PID); \
	fi
	@echo "■  Deteniendo frontend..."
	@pkill -f "vite" 2>/dev/null || true
	@echo "■  Listo."

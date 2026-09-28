.DEFAULT_GOAL := help
.PHONY: help frontend backend stop stop-frontend stop-backend status

help:
	@echo "make frontend       Restart frontend in this terminal (port 5173)"
	@echo "make backend        Restart backend in this terminal (port 8000)"
	@echo "make stop           Stop this project's frontend and backend"
	@echo "make stop-frontend  Stop only this project's frontend"
	@echo "make stop-backend   Stop only this project's backend"
	@echo "make status         Show running project server PIDs"

frontend:
	@bash scripts/frontend.sh start

backend:
	@bash scripts/backend.sh start

stop:
	@bash scripts/frontend.sh stop
	@bash scripts/backend.sh stop

stop-frontend:
	@bash scripts/frontend.sh stop

stop-backend:
	@bash scripts/backend.sh stop

status:
	@bash scripts/frontend.sh status
	@bash scripts/backend.sh status

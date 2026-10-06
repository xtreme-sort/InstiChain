.DEFAULT_GOAL := help
.PHONY: help frontend backend stop stop-frontend stop-backend status fabric-install fabric-up fabric-down fabric-gateway

help:
	@echo "make frontend       Restart frontend in this terminal (port 5173)"
	@echo "make backend        Restart backend in this terminal (port 8000)"
	@echo "make stop           Stop this project's frontend and backend"
	@echo "make stop-frontend  Stop only this project's frontend"
	@echo "make stop-backend   Stop only this project's backend"
	@echo "make status         Show running project server PIDs"
	@echo "make fabric-install Download pinned Fabric runtime and samples"
	@echo "make fabric-up      Start two-organization Fabric and deploy chaincode"
	@echo "make fabric-down    Stop and remove the local Fabric network"
	@echo "make fabric-gateway Start the local Fabric Gateway bridge"

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

fabric-install:
	@bash scripts/fabric.sh install

fabric-up:
	@bash scripts/fabric.sh up

fabric-down:
	@bash scripts/fabric.sh down

fabric-gateway:
	@bash scripts/fabric.sh gateway

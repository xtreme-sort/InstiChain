.DEFAULT_GOAL := help
.PHONY: help docker status-docker database status-database stop-database frontend backend mailpit stop stop-frontend stop-backend stop-mailpit status status-mailpit fabric-install fabric-up fabric-upgrade fabric-down fabric-test backend-test frontend-test test

help:
	@echo "make docker         Start the Docker daemon on Linux"
	@echo "make status-docker  Check whether the Docker daemon is available"
	@echo "make database       Start PostgreSQL and wait until it is healthy"
	@echo "make status-database Show the PostgreSQL container status"
	@echo "make stop-database  Stop PostgreSQL while preserving its data"
	@echo "make frontend       Restart frontend in this terminal (port 5173)"
	@echo "make backend        Restart backend in this terminal (port 8000)"
	@echo "make mailpit        Start the local email server and inbox"
	@echo "make stop           Stop this project's frontend and backend"
	@echo "make stop-frontend  Stop only this project's frontend"
	@echo "make stop-backend   Stop only this project's backend"
	@echo "make stop-mailpit   Stop Mailpit while preserving its configuration"
	@echo "make status         Show app, database, mail, and Fabric status"
	@echo "make status-mailpit Show the Mailpit container status"
	@echo "make fabric-install Download pinned Fabric runtime and samples"
	@echo "make fabric-up      Start two-organization Fabric and deploy chaincode"
	@echo "make fabric-upgrade Upgrade an existing JS chaincode deployment to Java"
	@echo "make fabric-down    Delete the local Fabric network and ledger"
	@echo "make test           Run backend, chaincode, and frontend checks"

docker:
	@if docker info >/dev/null 2>&1; then \
		echo "Docker is already running."; \
	elif [ "$$(ps -p 1 -o comm= 2>/dev/null)" = systemd ] && command -v systemctl >/dev/null 2>&1; then \
		echo "Starting Docker with systemd..."; \
		sudo systemctl start docker; \
	elif command -v service >/dev/null 2>&1; then \
		echo "Starting Docker service..."; \
		sudo service docker start; \
	else \
		echo "Start Docker Desktop, then run 'make status-docker'." >&2; \
		exit 1; \
	fi
	@docker info >/dev/null 2>&1 || { echo "Docker did not become available." >&2; exit 1; }
	@echo "Docker is running."

status-docker:
	@docker info >/dev/null 2>&1 && echo "Docker is running." || { echo "Docker is stopped or inaccessible." >&2; exit 1; }

database:
	@docker compose up -d --wait db
	@echo "PostgreSQL is ready at 127.0.0.1:5432."

status-database:
	@docker compose ps db

stop-database:
	@docker compose stop db

frontend:
	@bash scripts/dev.sh frontend start

backend:
	@bash scripts/dev.sh backend start

mailpit:
	@docker compose up -d --wait mailpit
	@echo "Mailpit inbox: http://127.0.0.1:8025"

stop:
	@bash scripts/dev.sh frontend stop
	@bash scripts/dev.sh backend stop

stop-frontend:
	@bash scripts/dev.sh frontend stop

stop-backend:
	@bash scripts/dev.sh backend stop

stop-mailpit:
	@docker compose stop mailpit

status:
	@bash scripts/dev.sh frontend status
	@bash scripts/dev.sh backend status
	@if docker info >/dev/null 2>&1; then \
		echo "Docker is running."; \
		docker compose ps db mailpit; \
		for container in peer0.org1.example.com peer0.org2.example.com orderer.example.com; do \
			state="$$(docker inspect --format '{{.State.Status}}' "$$container" 2>/dev/null || echo absent)"; \
			echo "$$container: $$state"; \
		done; \
	else \
		echo "Docker is stopped or inaccessible; database, Mailpit, and Fabric cannot be checked."; \
	fi

status-mailpit:
	@docker compose ps mailpit

fabric-install:
	@bash scripts/fabric.sh install

fabric-up:
	@bash scripts/fabric.sh up

fabric-upgrade:
	@bash scripts/fabric.sh upgrade

fabric-down:
	@bash scripts/fabric.sh down

backend-test:
	@cd backend && ./mvnw test

fabric-test:
	@GRADLE_USER_HOME="$(CURDIR)/.gradle" ./fabric/chaincode/gradlew -p fabric/chaincode test

frontend-test:
	@cd frontend && npm run build

test: backend-test fabric-test frontend-test

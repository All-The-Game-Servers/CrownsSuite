.PHONY: help dev-db dev-db-down migrate bootstrap-ca run-central run-keeper mint-token build test tidy

help:
	@echo "ATGS dev commands:"
	@echo "  make dev-db        Bring up local Postgres in Docker"
	@echo "  make dev-db-down   Tear down local Postgres"
	@echo "  make migrate       Apply database migrations"
	@echo "  make bootstrap-ca  Generate the internal CA (one-time per dev env)"
	@echo "  make run-central   Run the Central Server"
	@echo "  make mint-token    Mint a Keeper enrollment token"
	@echo "  make run-keeper    Run a Keeper daemon (requires ATGS_ENROLL_TOKEN)"
	@echo "  make build         Build both binaries"
	@echo "  make test          Run unit tests"

dev-db:
	docker compose -f deploy/postgres/docker-compose.yml up -d
	@echo "Waiting for Postgres..."
	@sleep 2

dev-db-down:
	docker compose -f deploy/postgres/docker-compose.yml down

migrate:
	go run ./central/cmd/central migrate

bootstrap-ca:
	go run ./central/cmd/central bootstrap-ca

run-central:
	go run ./central/cmd/central serve

mint-token:
	@go run ./central/cmd/central mint-enrollment-token

run-keeper:
	@test -n "$(ATGS_ENROLL_TOKEN)" || (echo "ATGS_ENROLL_TOKEN not set"; exit 1)
	go run ./keeper/cmd/keeper

build:
	go build -o bin/central ./central/cmd/central
	go build -o bin/keeper ./keeper/cmd/keeper

test:
	go test ./...

tidy:
	cd central && go mod tidy
	cd keeper && go mod tidy
	cd shared && go mod tidy

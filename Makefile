JAVA_HOME ?= $(HOME)/.sdkman/candidates/java/25.0.3-tem
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)
MVNW := ./codex/mvnw.sh

.PHONY: build test run clean coverage docker-build docker-run test-single help

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

build: ## Compile and run all tests
	$(MVNW) verify

test: ## Run tests only
	$(MVNW) test

run: ## Start server on localhost:8080
	$(MVNW) -pl :app -am -DskipTests package
	./modules/application/app/target/app/bin/app

clean: ## Clean build outputs
	$(MVNW) clean

coverage: ## Run tests with JaCoCo coverage report
	$(MVNW) verify -Pcoverage

test-single: ## Run a single test class (TEST=com.nyx.transcode.FFmpegProcessTest)
	@test -n "$(TEST)" || (echo "Usage: make test-single TEST=com.nyx.SomeTest" && exit 1)
	$(MVNW) -Dtest=$(TEST) test

docker-build: ## Build Docker image
	docker build -t nyx-media-streamer .

docker-run: ## Run Docker container
	docker run --rm -p 8080:8080 nyx-media-streamer

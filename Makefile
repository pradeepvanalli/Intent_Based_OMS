JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

.PHONY: help try reset down logs doctor infra build test run ui demo load clean docker helm-lint e2e

help:             ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

# ---- try it with Docker only (no Java/Node needed) ---------------------------------------------------------------
try:              ## build + start everything in containers, wait, print logins, open the browser (scripts/try.sh)
	./scripts/try.sh

reset:            ## wipe the database volume and start again (asks first; make reset YES=1 skips the question)
	./scripts/try.sh --reset $(if $(YES),--yes,)

down:             ## stop the containers started by `make try` (data is kept)
	./scripts/try.sh --down

logs:             ## follow the service log of the container started by `make try`
	./scripts/try.sh --logs

doctor:           ## check docker, compose, ports, JDK 21, node 22, memory and print fixes
	./scripts/doctor.sh

# ---- source build (JDK 21, Maven, Node 22 on this machine) ----------------------------------------------------------
infra:            ## start Postgres (5440) and Redis
	docker compose up -d postgres redis

build:            ## build all backend modules (skip tests)
	cd backend && mvn -q -DskipTests clean install

test:             ## run all backend tests (Docker required)
	cd backend && mvn -q test

ui:               ## build the React UI into the backend static folder
	./scripts/build-ui.sh

run: infra        ## run the service locally on :8080
	cd backend/oms-app && mvn -q spring-boot:run

e2e:              ## Playwright end-to-end suite on a fresh e2e database (scripts/e2e.sh; ARGS="--skip-build" to reuse the build)
	./scripts/e2e.sh $(ARGS)

demo:             ## drive the demo scenario against a running service
	./scripts/demo.sh

load:             ## k6 load test for promise + capture
	k6 run load/promise.js

docker:           ## build and run everything in containers (same as `make try` without the waiting and the browser)
	docker compose up --build -d

helm-lint:        ## lint the Helm chart (uses the alpine/helm image when helm is not installed)
	@if command -v helm >/dev/null 2>&1; then helm lint deploy/helm/intent-oms; \
	else docker run --rm -v "$(CURDIR)/deploy/helm/intent-oms:/chart:ro" alpine/helm:3 lint /chart; fi

clean:
	cd backend && mvn -q clean

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

ifeq ($(origin JAVA_HOME), undefined)
  JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
endif
export JAVA_HOME

# One seed for the whole build's test-class order (root pom: surefire.runOrder=random).
# A fresh one per build unless given: replay a CI or local order with TEST_SEED=<seed>.
ifeq ($(strip $(TEST_SEED)),)
  TEST_SEED := $(shell echo $$(( (RANDOM << 15) | RANDOM )))
endif
export TEST_SEED
TEST_SEED_BANNER = echo ""; \
	echo "==> Test classes run in random order, seed $(TEST_SEED). Replay: make $@ TEST_SEED=$(TEST_SEED)"; \
	echo ""
MVN_TEST_SEED = -Dsurefire.runOrder.random.seed=$(TEST_SEED)

.PHONY: help jdk-check hooks up up-all down clean logs psql migrate local-secrets verify verify-all verify-cold verify-module rebuild-projection test-fixtures-clean fmt lint k8s-up k8s-down k8s-status gke-up gke-deploy gke-tunnel gke-status gke-down skaffold-dev skaffold-run skaffold-delete demo-tools-build demo-port-forward demo-seed demo-day demo-eod demo-statements demo-onboard demo-reset

.DEFAULT_GOAL := help

help: ## Show help for each target
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

hooks: ## Install the local git hooks (.githooks/pre-push runs make verify before a push)
	git config core.hooksPath .githooks
	@echo "core.hooksPath set to .githooks. Bypass a single push with: SKIP_VERIFY=1 git push"

up: ## Start core infrastructure services and wait until healthy
	docker compose -f infrastructure/compose/docker-compose.yml --profile infra up -d
	@elapsed=0; \
	timeout=120; \
	expected=6; \
	while [ $$elapsed -lt $$timeout ]; do \
		healthy=$$(docker compose -f infrastructure/compose/docker-compose.yml ps | grep -c "(healthy)" || true); \
		echo "Waiting for core infrastructure services to become healthy ($$healthy/$$expected healthy, elapsed: $${elapsed}s)..."; \
		if [ "$$healthy" -ge "$$expected" ]; then \
			echo "All core infrastructure services are healthy!"; \
			exit 0; \
		fi; \
		sleep 2; \
		elapsed=$$((elapsed + 2)); \
	done; \
	echo "ERROR: Core infrastructure services failed to report healthy within 120s" >&2; \
	exit 1

up-all: ## Start all infrastructure services (including Keycloak and observability) and wait until healthy
	docker compose -f infrastructure/compose/docker-compose.yml --profile all up -d
	@elapsed=0; \
	timeout=120; \
	expected=9; \
	while [ $$elapsed -lt $$timeout ]; do \
		healthy=$$(docker compose -f infrastructure/compose/docker-compose.yml ps | grep -c "(healthy)" || true); \
		echo "Waiting for all infrastructure services to become healthy ($$healthy/$$expected healthy, elapsed: $${elapsed}s)..."; \
		if [ "$$healthy" -ge "$$expected" ]; then \
			echo "All infrastructure services are healthy!"; \
			exit 0; \
		fi; \
		sleep 2; \
		elapsed=$$((elapsed + 2)); \
	done; \
	echo "ERROR: Infrastructure services failed to report healthy within 120s" >&2; \
	exit 1

down: ## Stop all infrastructure services
	docker compose -f infrastructure/compose/docker-compose.yml --profile all down

clean: ## Stop services, remove volumes and ledger data (destructive)
	@if [ "$(FORCE)" != "1" ]; then \
		echo "WARNING: This will stop containers, remove docker volumes, and delete infrastructure/compose/data (all ledger and database state)."; \
		read -p "Are you sure you want to proceed? [y/N] " confirm; \
		if [ "$$confirm" != "y" ] && [ "$$confirm" != "Y" ]; then \
			echo "Aborted."; \
			exit 1; \
		fi; \
	fi
	docker compose -f infrastructure/compose/docker-compose.yml --profile all down -v --remove-orphans
	rm -rf infrastructure/compose/data

logs: ## Follow logs for a service (usage: make logs s=<service>)
	@if [ -z "$(s)" ]; then \
		echo "Usage: make logs s=<service> (e.g. make logs s=postgres)"; \
		exit 1; \
	fi
	docker compose -f infrastructure/compose/docker-compose.yml logs -f $(s)

psql: ## Open a psql shell in the postgres container
	docker compose -f infrastructure/compose/docker-compose.yml exec postgres psql -U funds -d funds

migrate: ## Show how database migrations are applied
	@echo "Flyway migrations live in libs/db-migrations/src/main/resources/db/migration,"
	@echo "laid out per schema (ADR-0011). The four schemas are one shared database, so the"
	@echo "tree is a library every service depends on rather than one service's private copy,"
	@echo "and version numbers are a single global sequence across services."
	@echo "They are applied by each service itself at startup, so a deployed instance can"
	@echo "never serve traffic against a schema it has not migrated. There is deliberately"
	@echo "no separate apply step to forget to run."

local-secrets: ## Generate the local cluster's TLS and Linkerd keys into infrastructure/k8s/overlays/local/generated/ (gitignored; only when absent), and the GKE overlay's random credentials into overlays/gke/generated/
	./infrastructure/k8s/bootstrap/local-secrets.sh
	./infrastructure/gke/secrets.sh

jdk-check: ## Fail unless JAVA_HOME (or java on PATH) is a Temurin JDK -- Homebrew openjdk@21 crashes Spotless
	@echo "JAVA_HOME=$(JAVA_HOME)"
	@java_bin="$${JAVA_HOME:+$$JAVA_HOME/bin/}java"; \
	vendor=$$("$$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.vendor = //p'); \
	if [ "$$vendor" != "Eclipse Adoptium" ]; then \
		echo "ERROR: $$java_bin is '$$vendor', not Temurin (Eclipse Adoptium). CI builds on Temurin 21;" >&2; \
		echo "       point JAVA_HOME at a Temurin 21 JDK (Homebrew openjdk@21 crashes Spotless)." >&2; \
		exit 1; \
	fi

verify: local-secrets jdk-check ## Run system verification checks (excludes integration-tagged tests). Random class order; replay with TEST_SEED=<seed>
	@$(TEST_SEED_BANNER)
	mvn verify $(MVN_TEST_SEED) || { $(TEST_SEED_BANNER); exit 1; }
	@$(TEST_SEED_BANNER)

verify-all: local-secrets jdk-check ## Run every check incl. integration-tagged tests, as the CI integration job does: container reuse off, every failing module reported (-fae). Replay a CI order with TEST_SEED=<seed>
	@$(TEST_SEED_BANNER)
	TESTCONTAINERS_REUSE_ENABLE=$${TESTCONTAINERS_REUSE_ENABLE:-false} mvn -fae verify -Pintegration $(MVN_TEST_SEED) || { $(TEST_SEED_BANNER); exit 1; }
	@$(TEST_SEED_BANNER)

verify-cold: local-secrets jdk-check ## Run make verify with container reuse off, as CI does
	@$(TEST_SEED_BANNER)
	TESTCONTAINERS_REUSE_ENABLE=false mvn verify $(MVN_TEST_SEED) || { $(TEST_SEED_BANNER); exit 1; }
	@$(TEST_SEED_BANNER)

verify-module: local-secrets ## Fast local loop: verify one module + its dependencies only (usage: make verify-module MODULE=services/balance-api). Not a substitute for `make verify` before a PR -- it does not test modules that depend on what changed.
	@if [ -z "$(MODULE)" ]; then \
		echo "Usage: make verify-module MODULE=<path> (e.g. make verify-module MODULE=services/balance-api)"; \
		exit 1; \
	fi
	@echo "JAVA_HOME=$(JAVA_HOME)"
	mvn verify -pl $(MODULE) -am

rebuild-projection: ## Rebuild the projection from CDC timestamp-last=0 and assert byte-identical state (Task 5.8 / 30b2; heavy, nightly)
	@echo "JAVA_HOME=$(JAVA_HOME)"
	@echo "Rebuilding projection.transfer + projection.participant_units from CDC timestamp-last=0."
	@echo "Truncates every projection table, replays the whole CDC log, asserts an exact match."
	@echo "Override the transfer count with REBUILD_TRANSFERS=<n> for a faster local run."
	mvn -q -pl services/projection-worker -am install -DskipTests -Dspotless.check.skip=true
	mvn -pl services/projection-worker test -Pintegration \
		-Dtest=RebuildProjectionIntegrationTest -DfailIfNoTests=false \
		$(if $(REBUILD_TRANSFERS),-Drebuild.transferCount=$(REBUILD_TRANSFERS),)
	@echo ""
	@echo "Rebuild rate (also at services/projection-worker/target/rebuild-metrics.prom):"
	@cat services/projection-worker/target/rebuild-metrics.prom

test-fixtures-clean: ## Remove reused Testcontainers fixtures (needed after changing fixture config)
	@echo "Removing reusable test fixture containers..."
	@ids=$$(docker ps -aq --filter "label=org.testcontainers.hash"); \
	if [ -n "$$ids" ]; then \
		docker rm -f $$ids; \
	else \
		echo "None running."; \
	fi

fmt: ## Format code using Spotless
	mvn spotless:apply

lint: ## Check code formatting using Spotless
	mvn spotless:check

verify-k8s: local-secrets ## Validate Kubernetes manifests, security policies, and schemas (Task 12.1 / Task 12.2)
	@echo "Validating Kubernetes base and overlay manifests..."
	@if command -v kubectl >/dev/null 2>&1; then \
		kubectl kustomize infrastructure/k8s/base > /dev/null && \
		kubectl kustomize infrastructure/k8s/overlays/local > /dev/null && \
		kubectl kustomize infrastructure/k8s/overlays/prod > /dev/null && \
		kubectl kustomize infrastructure/k8s/overlays/gke > /dev/null && \
		kubectl kustomize infrastructure/k8s/overlays/gke-small > /dev/null; \
	fi
	@! grep -r "kind: Secret$$" infrastructure/k8s/base/ infrastructure/k8s/overlays/
	mvn test -pl libs/e2e-tests -Dtest="KustomizeBaseValidationTest,KustomizeOverlaysValidationTest"

k8s-up: ## Create the local kind cluster and install its operators (Calico, metrics-server, CloudNativePG, RabbitMQ); then `make skaffold-run`
	@# Shares the Docker VM with the Testcontainers fixtures; both at once need ~8 GB.
	./infrastructure/k8s/bootstrap/up.sh

k8s-down: ## Delete the local kind cluster and everything in it
	./infrastructure/k8s/bootstrap/down.sh

k8s-status: ## Pods, operators and HPAs on the local cluster
	kubectl --context kind-fondaria -n fondaria get pods,hpa
	kubectl --context kind-fondaria -n fondaria get cluster.postgresql.cnpg.io,rabbitmqcluster

gke-up: ## Create the throwaway GKE load-test environment in a new project, or in GKE_PROJECT if set (ADR-0036; needs GKE_BILLING_ACCOUNT). Builds only the cheap stateless pool; shuts itself down after GKE_TTL_MINUTES (default 120)
	./infrastructure/gke/up.sh

gke-deploy: local-secrets ## Build and push amd64 images, THEN create the stateful pool (re-arms the switch to GKE_STATEFUL_TTL_MINUTES, default 90) and deploy overlays/gke; prints the cost window
	./infrastructure/gke/deploy.sh

gke-tunnel: ## Port-forward Grafana (30001) and the three demo APIs (30080-30082) from the GKE environment
	./infrastructure/gke/tunnel.sh

gke-status: ## Nodes, pods and stateful clusters on the GKE environment
	. infrastructure/gke/.state && kubectl --context "$$GKE_CONTEXT" get nodes -L fondaria.example/node-pool,topology.kubernetes.io/zone && \
	kubectl --context "$$GKE_CONTEXT" -n fondaria get pods -o wide && \
	kubectl --context "$$GKE_CONTEXT" -n fondaria get cluster.postgresql.cnpg.io,rabbitmqcluster

gke-down: ## Delete the GKE environment (its whole project, or only what gke-up built in a kept GKE_PROJECT) and revoke this machine's gcloud login (ARGS=--keep-login to keep it)
	./infrastructure/gke/down.sh $(ARGS)

skaffold-dev: local-secrets ## Run Skaffold in development mode with live sync, port-forwarding and log streaming (Task 12.6)
	skaffold dev --kube-context kind-fondaria --port-forward

skaffold-run: local-secrets ## Build and deploy all services to the local cluster via Skaffold and the local Kustomize overlay (Task 12.6)
	skaffold run --kube-context kind-fondaria

skaffold-delete: ## Clean up and delete all Skaffold-deployed resources leaving no residue (Task 12.6)
	skaffold delete --kube-context kind-fondaria

DEMO_TOOLS_CP := libs/demo-tools/target/classes:libs/platform-common/target/classes

demo-tools-build: ## Compile libs/demo-tools (Task D.2), used by the demo-* targets below
	mvn -q -pl libs/demo-tools -am compile -DskipTests -Dspotless.check.skip=true

demo-port-forward: ## Ensure background port-forwards for the three demo APIs (30080, 30081, 30082) are active
	@if ! curl -s -f http://localhost:30080/actuator/health >/dev/null 2>&1 || \
	    ! curl -s -f http://localhost:30081/actuator/health >/dev/null 2>&1 || \
	    ! curl -s -f http://localhost:30082/actuator/health >/dev/null 2>&1; then \
		pkill -f "kubectl.*port-forward.*(30080|30081|30082)" 2>/dev/null || true; \
		nohup sh -c 'while true; do kubectl port-forward -n fondaria svc/contribution-api 30080:80 >/dev/null 2>&1; sleep 1; done' </dev/null >/dev/null 2>&1 & \
		nohup sh -c 'while true; do kubectl port-forward -n fondaria svc/balance-api 30081:80 >/dev/null 2>&1; sleep 1; done' </dev/null >/dev/null 2>&1 & \
		nohup sh -c 'while true; do kubectl port-forward -n fondaria svc/valuation-api 30082:80 >/dev/null 2>&1; sleep 1; done' </dev/null >/dev/null 2>&1 & \
		sleep 2; \
	fi

demo-seed: demo-tools-build demo-port-forward ## Seed the demo: 3 funds, 12 employers, ~300,000 participants (Task D.2). ORGS=8 seeds only the 8 smallest (~60,000); re-run without ORGS to add the rest. Run against a fresh `make k8s-up && make skaffold-run`.
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools seed $(if $(ORGS),--organizations $(ORGS))

demo-day: demo-tools-build demo-port-forward ## One simulated day: advance one business day, contributions, a scripted NAV move, then the day's close (sign-off, reconciliation, hash chain; statements too on a month end)
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools advance --business-days 1
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools contributions
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools valuations
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools close

demo-eod: demo-tools-build ## Close the current simulated day, or show the close it already has: moves the clock into eod-job's close window and prints the sign-off
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools close

demo-statements: demo-tools-build demo-port-forward ## Advance to the month's last business day, run and close it, then print a hero participant's monthly statement from the object store
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools advance --to month-end
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools contributions
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools valuations
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools close

demo-onboard: demo-tools-build demo-port-forward ## Onboard a 200,000-employee employer live and watch it allot (Task D.2)
	java -cp $(DEMO_TOOLS_CP) dev.fondaria.demo.DemoTools onboard --employees 200000

demo-reset: ## Reset and seed the local demo cluster from scratch (Task D.3)
	rm -rf demo/out
	$(MAKE) k8s-down
	$(MAKE) k8s-up
	$(MAKE) skaffold-run
	$(MAKE) demo-seed





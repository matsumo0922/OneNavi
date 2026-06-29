#!/usr/bin/make -f

SPEED ?= 60
DEV_TOOLS_DIR := dev-tools
# Vite dev server (GPS ブリッジ middleware を内包) のポート
DEV_TOOLS_PORT := 5173

.PHONY: detekt dhu route-demo-1 clean-dhu ext-api-setup live-test dev-tools dev-tools-setup dev-tools-dev dev-tools-status dev-tools-stop

detekt:
	./gradlew detekt --auto-correct --continue

dhu:
	~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb -c ~/Library/Android/sdk/extras/google/auto/config/default_720p.ini

# ── External API local dependency ──

ext-api-setup:
	scripts/setup_ext_api.sh

# ── Server Route API live test ──

live-test:
	@if [ ! -f .env ]; then \
		echo "[live-test] .env is required. Set SERVER_ROUTE_BASE_URL and Cloudflare Access header op:// refs."; \
		exit 1; \
	fi
	op run --env-file=.env -- env SERVER_ROUTE_LIVE_TESTS=true ./gradlew :core:navigation:testDebugUnitTest \
		--tests 'me.matsumo.onenavi.core.navigation.server.GuidanceApiClientLiveTest' \
		--rerun-tasks \
		--no-configuration-cache

# ── Dev Tools (Android Emulator GPS) ──

dev-tools-setup:
	cd $(DEV_TOOLS_DIR) && npm install
	@if [ ! -f $(DEV_TOOLS_DIR)/.env ]; then \
		cp $(DEV_TOOLS_DIR)/.env.example $(DEV_TOOLS_DIR)/.env; \
		echo "[dev-tools] .env created. Set VITE_GOOGLE_API_KEY in $(DEV_TOOLS_DIR)/.env"; \
	fi

dev-tools-dev:
	cd $(DEV_TOOLS_DIR) && npx vite

dev-tools: dev-tools-setup dev-tools-dev

dev-tools-status:
	@curl -s --max-time 3 http://localhost:$(DEV_TOOLS_PORT)/status | python3 -m json.tool 2>/dev/null || echo "[dev-tools] Not connected (dev server not running?)"

dev-tools-stop:
	@curl -s --max-time 3 -X POST http://localhost:$(DEV_TOOLS_PORT)/stop | python3 -m json.tool 2>/dev/null || echo "[dev-tools] Not connected"

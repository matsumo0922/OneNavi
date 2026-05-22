#!/usr/bin/make -f

SPEED ?= 60
FAKE_GPS_DIR := dev-tools/fake-gps
# Vite dev server (GPS ブリッジ middleware を内包) のポート
FAKE_GPS_PORT := 5173
ROUTE_COMPARE_DIR := dev-tools/route-compare
UI_PLAYGROUND_DIR := dev-tools/ui-playground

.PHONY: detekt dhu route-demo-1 clean-dhu fake-gps fake-gps-setup fake-gps-dev fake-gps-status fake-gps-stop route-compare route-compare-setup route-compare-dev ui-playground ui-playground-setup ui-playground-dev

detekt:
	./gradlew detekt --auto-correct --continue

dhu:
	@if [ ! -p dhu_pipe ]; then mkfifo dhu_pipe; fi
	tail -f dhu_pipe | ~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb -c ~/Library/Android/sdk/extras/google/auto/config/default_720p.ini

route-demo-1:
	python3 scripts/gpx_to_dhu.py scripts/route/yamata.gpx --speed $(SPEED) --pipe dhu_pipe

clean-dhu:
	rm -f dhu_pipe

# ── Fake GPS (Android Emulator) ──

fake-gps-setup:
	cd $(FAKE_GPS_DIR) && npm install
	@if [ ! -f $(FAKE_GPS_DIR)/.env ]; then \
		cp $(FAKE_GPS_DIR)/.env.example $(FAKE_GPS_DIR)/.env; \
		echo "[fake-gps] .env created. Set VITE_GOOGLE_API_KEY in $(FAKE_GPS_DIR)/.env"; \
	fi

fake-gps-dev:
	cd $(FAKE_GPS_DIR) && npx vite

fake-gps: fake-gps-setup fake-gps-dev

fake-gps-status:
	@curl -s --max-time 3 http://localhost:$(FAKE_GPS_PORT)/status | python3 -m json.tool 2>/dev/null || echo "[fake-gps] Not connected (dev server not running?)"

fake-gps-stop:
	@curl -s --max-time 3 -X POST http://localhost:$(FAKE_GPS_PORT)/stop | python3 -m json.tool 2>/dev/null || echo "[fake-gps] Not connected"

# ── Route Compare (debug) ──

route-compare-setup:
	cd $(ROUTE_COMPARE_DIR) && npm install
	@if [ ! -f $(ROUTE_COMPARE_DIR)/.env ]; then \
		cp $(ROUTE_COMPARE_DIR)/.env.example $(ROUTE_COMPARE_DIR)/.env; \
		echo "[route-compare] .env created. Set VITE_GOOGLE_API_KEY in $(ROUTE_COMPARE_DIR)/.env"; \
	fi

route-compare-dev:
	cd $(ROUTE_COMPARE_DIR) && npx vite

route-compare: route-compare-setup route-compare-dev

# ── UI Playground (design preview) ──

ui-playground-setup:
	cd $(UI_PLAYGROUND_DIR) && npm install

ui-playground-dev:
	cd $(UI_PLAYGROUND_DIR) && npx vite

ui-playground: ui-playground-setup ui-playground-dev

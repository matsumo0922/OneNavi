#!/usr/bin/make -f

SPEED ?= 60
FAKE_GPS_DIR := dev-tools/fake-gps
FAKE_GPS_PORT := 5556

.PHONY: detekt dhu route-demo-1 clean-dhu fake-gps fake-gps-setup fake-gps-dev fake-gps-forward fake-gps-status fake-gps-stop

detekt:
	./gradlew detekt --auto-correct --continue

dhu:
	@if [ ! -p dhu_pipe ]; then mkfifo dhu_pipe; fi
	tail -f dhu_pipe | ~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb -c ~/Library/Android/sdk/extras/google/auto/config/default_720p.ini

route-demo-1:
	python3 scripts/gpx_to_dhu.py scripts/route/yamata.gpx --speed $(SPEED) --pipe dhu_pipe

clean-dhu:
	rm -f dhu_pipe

# ── Fake GPS ──

fake-gps-setup:
	cd $(FAKE_GPS_DIR) && npm install
	@if [ ! -f $(FAKE_GPS_DIR)/.env ]; then \
		cp $(FAKE_GPS_DIR)/.env.example $(FAKE_GPS_DIR)/.env; \
		echo "[fake-gps] .env created. Set VITE_GOOGLE_API_KEY in $(FAKE_GPS_DIR)/.env"; \
	fi

fake-gps-forward:
	adb forward tcp:$(FAKE_GPS_PORT) tcp:$(FAKE_GPS_PORT)
	@echo "[fake-gps] ADB forward tcp:$(FAKE_GPS_PORT) -> device tcp:$(FAKE_GPS_PORT)"

fake-gps-dev: fake-gps-forward
	cd $(FAKE_GPS_DIR) && npx vite

fake-gps: fake-gps-setup fake-gps-dev

fake-gps-status:
	@curl -s http://localhost:$(FAKE_GPS_PORT)/status | python3 -m json.tool 2>/dev/null || echo "[fake-gps] Not connected"

fake-gps-stop:
	@curl -s -X POST http://localhost:$(FAKE_GPS_PORT)/stop | python3 -m json.tool 2>/dev/null || echo "[fake-gps] Not connected"
	-adb forward --remove tcp:$(FAKE_GPS_PORT)

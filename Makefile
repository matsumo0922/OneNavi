#!/usr/bin/make -f

SPEED ?= 60

.PHONY: detekt dhu route-demo-1 clean-dhu

detekt:
	./gradlew detekt --auto-correct --continue

dhu:
	@if [ ! -p dhu_pipe ]; then mkfifo dhu_pipe; fi
	tail -f dhu_pipe | ~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb -c ~/Library/Android/sdk/extras/google/auto/config/default_720p.ini

route-demo-1:
	python3 scripts/gpx_to_dhu.py scripts/route/yamata.gpx --speed $(SPEED) --pipe dhu_pipe

clean-dhu:
	rm -f dhu_pipe

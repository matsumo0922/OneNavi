#!/usr/bin/make -f

detekt:
	./gradlew detekt --auto-correct --continue

route-demo-1:
    python3 scripts/gpx_to_dhu.py scripts/route/yamata.gpx | ~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb -c ~/Library/Android/sdk/extras/google/auto/config/default_720p.ini

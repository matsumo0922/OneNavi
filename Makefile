#!/usr/bin/make -f

detekt:
	./gradlew detekt --auto-correct --continue

# armorgram APK build (NixOS-friendly).
#
# Outside a nix-shell, every target transparently re-runs inside `nix-shell`
# (see shell.nix) so the JDK + Android SDK are available. Inside a nix-shell
# (IN_NIX_SHELL set), gradle runs directly.
#
#   make debug      build the debug APK (no signing/secrets needed)
#   make install    build + adb install onto a connected device
#   make apk        print the path to the built debug APK
#   make clean      gradle clean
#   make release    build the release APK (needs keystore + env, see below)
#   make shell      enter the Nix dev shell

MODULE      := presentation
FLAVOR      := noAnalytics
# Gradle task names capitalize the flavor: assembleNoAnalyticsDebug.
FLAVOR_CAP  := NoAnalytics
GRADLE      := ./gradlew
GRADLE_ARGS := --console=plain --stacktrace

DEBUG_APK := $(MODULE)/build/outputs/apk/$(FLAVOR)/debug/$(MODULE)-$(FLAVOR)-debug.apk

# Run a command directly when already inside a nix-shell, otherwise wrap it.
ifdef IN_NIX_SHELL
  WRAP = $(1)
else
  WRAP = nix-shell --run '$(1)'
endif

.PHONY: help debug release clean install apk shell
.DEFAULT_GOAL := help

help:
	@echo "armorgram build targets:"
	@echo "  make debug     build the debug APK ($(FLAVOR))"
	@echo "  make install   build + adb install the debug APK"
	@echo "  make apk       print the path to the built debug APK"
	@echo "  make clean     gradle clean"
	@echo "  make release   build the release APK (needs keystore + signing env)"
	@echo "  make shell     enter the Nix dev shell"
	@echo ""
	@echo "On NixOS, targets auto-run inside nix-shell unless IN_NIX_SHELL is set."

debug:
	$(call WRAP,$(GRADLE) $(GRADLE_ARGS) assemble$(FLAVOR_CAP)Debug)
	@echo "APK: $(DEBUG_APK)"

# Release signing is only wired when CI=true (see presentation/build.gradle):
# put the keystore at ./keystore and export keystore_password / key_alias /
# key_password, then: CI=true make release
release:
	$(call WRAP,$(GRADLE) $(GRADLE_ARGS) assemble$(FLAVOR_CAP)Release)

clean:
	$(call WRAP,$(GRADLE) $(GRADLE_ARGS) clean)

install: debug
	$(call WRAP,adb install -r $(DEBUG_APK))

apk:
	@echo $(DEBUG_APK)

shell:
	nix-shell

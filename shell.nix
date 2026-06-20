# Reproducible Android build environment for armorgram on NixOS.
#
#   nix-shell           # enter the env (sets JDK/SDK, writes local.properties)
#   make debug          # build the debug APK
#
# Pinned to the toolchain this project expects: Gradle 5.4.1 (wrapper),
# AGP 3.5.4, build-tools 29.0.3, compileSdk 29, JDK 8.
{ pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  }
}:

let
  # aapt2 used for the override comes from the build-tools the app module declares.
  buildToolsVersion = "29.0.3";

  androidComposition = pkgs.androidenv.composeAndroidPackages {
    # platform 29 for app/data/common/domain; platform 25 for android-smsmms.
    platformVersions = [ "25" "29" ];
    # 29.0.3 is declared by :presentation; the other modules fall back to AGP
    # 3.5.4's default (28.0.3). Both must be present so AGP doesn't try to
    # auto-install into the read-only Nix store.
    buildToolsVersions = [ "28.0.3" "29.0.3" ];
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
  };

  sdk = androidComposition.androidsdk;
  sdkRoot = "${sdk}/libexec/android-sdk";
  aapt2 = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
in
pkgs.mkShell {
  name = "armorgram-android";

  buildInputs = [
    pkgs.jdk8
    sdk
  ];

  JAVA_HOME = "${pkgs.jdk8}";
  ANDROID_SDK_ROOT = sdkRoot;
  ANDROID_HOME = sdkRoot;

  # AGP 3.5.4 downloads a native aapt2 from Google Maven that will not run on
  # NixOS (no FHS). Point it at the patched aapt2 from the Nix build-tools.
  # Set as a Gradle *project* property via org.gradle.project.* so it applies
  # whether you run `make` (which wraps nix-shell) or `./gradlew` directly.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";

  shellHook = ''
    # Gradle reads the SDK location from local.properties (gitignored).
    echo "sdk.dir=${sdkRoot}" > local.properties
    echo "armorgram dev shell:"
    echo "  JDK   : $(${pkgs.jdk8}/bin/java -version 2>&1 | head -n1)"
    echo "  SDK   : ${sdkRoot}"
    echo "  build : make debug   (or ./gradlew assembleNoAnalyticsDebug)"
  '';
}

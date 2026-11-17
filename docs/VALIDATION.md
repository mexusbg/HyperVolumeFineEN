# English edition validation

Validation performed on November 17, 2026, for version **2.10-en.1**, version code **22**.

## Build and package

- Built with JDK 17.0.19, Android platform 36, build tools 36.0.0, Python 3.11, and libxposed API 102.0.0 from Maven Central.
- Completed aapt2 resource compilation/linking, javac, d8, packaging, alignment, and signing. Javac reports existing deprecated-API use in the settings activity; compilation succeeds.
- `apksigner verify --verbose --min-sdk-version 28` passes (APK Signature Scheme v3).
- `zipalign -c -p 4` passes.
- Manifest inspection confirms package `io.github.usama6878786.hypervolumefine`, English app/activity labels, version code 22, minimum SDK 28, target SDK 36, and the launcher-icon reference.
- All 25 Android XML files parse. aapt2 links the adaptive, round, and Android 13+ monochrome icon resources successfully.
- The APK contains `classes.dex`, `META-INF/xposed/module.prop`, `java_init.list`, and `scope.list`, with API 102 metadata and the original `system` / `com.android.systemui` scope.
- `bash -n build.sh` and `git diff --check` pass. No Chinese ideographs or full-width Chinese punctuation remain in project source, resources, scripts, or documentation.

The generated APK is a locally development-signed build in `dist/HyperVolumeFineEN-2.10-en.1.apk`; it is not signed with the upstream author's certificate.

## Emulator UI checks

- Installed and launched the APK on an Android 16/API 36 emulator.
- Visually inspected the English header, enable card, volume controls, number toggle, parameter labels/values/help, explanatory text, reset button, and version footer.
- Captured actual [light](screenshots/settings-light.png) and [dark](screenshots/settings-dark.png) screenshots at 1080 × 2400 pixels, density 420.
- Checked a narrow 320dp viewport (840 pixels at density 420) with font scale 1.3. Parameter headers stack labels above steppers in that configuration to preserve readable English labels.
- Checked the emulator crash log after app launches and theme/layout changes; no app crash was recorded.

The older Chinese settings/toast screenshot was replaced with the English screenshots. The upstream panel screenshot is retained because its only readable app content is the numeric level.

## Maintainer-reported hardware testing

The fork maintainer reports testing this English edition on **Xiaomi 17 Ultra, EEA variant, running HyperOS 4 / Android 17**. This device result was supplied by the maintainer; the automated checks above were performed separately on the emulator.

## Limits

The emulator does not have LSPosed or Xiaomi/HyperOS SystemUI. Its stock maximum is 15, as shown in the settings screenshots. This validates installation, resources, and UI rendering, **not** system-server hooks, audio-policy behavior, volume persistence, sender authentication, panel-number alignment on MIUI, or actual loudness.

The automated validation did not modify or reboot a rooted Xiaomi device. No quantitative hardware performance or loudness measurements are claimed for the maintainer-reported test. The inherited runtime issues in [OPTIMIZATIONS.md](OPTIMIZATIONS.md) remain open recommendations.

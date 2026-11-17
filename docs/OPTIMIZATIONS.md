# Optimization and correctness review

Reviewed against the source used for the English edition on November 17, 2026. These are **recommendations**, not implemented runtime fixes or measured speedups. The translation/icon change preserves audio-hook behavior. Fix correctness and access control before tuning performance.

All Java paths below are under `src/main/java/io/github/usama6878786/hypervolumefine/`.

## 1. Make stock-volume restoration selective and repeat-safe

**Priority: high — correctness.** `VolumeFineModule.restoreStockVolumeSettings()` scans every setting beginning with `volume_`, so it includes keys such as `volume_fine_steps` and `volume_fine_threshold`. With defaults, disabling can rewrite 100 configured levels to 29 and a threshold of 30 to 9. It also uses a fixed 29/100 ratio regardless of the selected maximum, and leaves media values at or below 29 unscaled (20/100 would become 20/29 rather than approximately 6/29).

Persist the actual previous maximum and original per-stream maximum; migrate only the selected streams' real device-volume keys. Exclude all `volume_fine_*` keys. Scale every relevant stored level with `oldLevel * stockMax / expandedMax`, preserve mute/zero, clamp to the target range, and record completion so a second reboot cannot rescale it again. Account for alias streams, per-device values, users, and a device whose stock maximum is not 29.

Validate with source maxima 20, 100, and 150; zero, low, and maximum levels; multiple devices; all configuration keys; and two consecutive disabled boots. Require restoration to complete before advising users to disable the LSPosed module.

## 2. Authenticate configuration before writing privileged settings

**Priority: high — access control.** `doRegisterConfigReceiver()` exports the receiver. `isFromModule()` returns true when the expected UID cannot be resolved, the reflected sender-UID call fails, or the sender UID is unavailable. `SettingsActivity.pushConfig()` sends a normal broadcast without explicitly requesting identity sharing. An unverified sender can therefore reach the allowlisted `Settings.System` writes. Values are also written before range validation.

For Android 14+, request sender identity sharing and reject unavailable or mismatched UIDs. Resolve the package UID for the correct user and refresh it after package changes. For older versions, use an authenticated IPC design with a trustworthy calling UID, rather than assuming broadcast extras prove identity; otherwise explicitly restrict supported configuration versions. Validate values before persistence. A package-targeted intent alone does not authenticate a caller.

The platform documents that sender identity can be unavailable and exposes explicit identity sharing in [BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid()) and [BroadcastOptions](https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean)).

Validate that broadcasts from an unrelated test package and missing identities are rejected, legitimate settings still save on each supported Android version, invalid values are rejected, and reinstall/multi-user cases resolve the correct UID.

## 3. Correct the slider-snapping units

**Priority: high — behavior.** `hookAbsoluteSet()` calls `snap(index, cfg.snap, max)` on indices where one user-visible level equals `STEP_UNIT` (10). A requested two-level grid is currently an internal grid of 2, or 0.2 user levels; ordinary whole-level positions are already multiples of that grid and may not snap at all.

If the UI is meant to describe user-visible levels, use `cfg.snap * STEP_UNIT` as the internal grid, keeping 1 as the disabled setting and retaining the key-path bypass. Guarantee that mute and the maximum remain reachable even when the maximum is not divisible by the grid; select the upper endpoint as a valid candidate near the top rather than relying only on an overshoot clamp.

Validate grid 2 with indices 130 and 150, grids 1–6, odd maxima, both endpoints, and fine key presses that intentionally do not match the slider grid.

## 4. Stop SystemUI polling when a reused panel is hidden

**Priority: medium — CPU and Binder traffic.** `PanelNumber.startPolling()` runs every 60ms and stops only when `mHost` becomes hidden/detached. The source comments explain that HyperOS may reuse a still-shown host even after its slider disappears. In that case `now == null` fades the number but does not stop the loop, and `refreshText()` continues querying media volume about 17 times per second. A disabled number also leaves the visible-host loop running.

Track actual panel/slider visibility and suspend expensive work when hidden or disabled. Resume from a reliable visibility/attach/layout event, including the reused-window case where `addView` is not called again. Use a scoped volume callback or panel state hook while visible; keep bounded polling as a compatibility fallback. Release stale host/slider references on detach.

Also avoid restarting a fade-in animation on every tick while alpha is below one: `fadeIn()` currently cancels/restarts an in-progress animation. Track its target or active animator and only start it on a visibility transition.

Measure SystemUI CPU, allocations, and Binder calls while dragging, after normal dismissal, after reused-window dismissal, and with number visibility disabled. Target no recurring volume reads once the panel is hidden. These are profiling targets, not measured gains.

## 5. Replace repeated settings reads with coherent change-driven snapshots

**Priority: medium — latency and thread safety.** `VolumeFineModule.config()` periodically queries settings after an 800ms cache expires, potentially from audio hooks; `PanelNumber` polls two switches once per second. The shared mutable configuration fields also have no consistent publication/invalidation mechanism across receiver and hook threads. A stale result can race a save invalidation.

After the system context/provider is ready, use a [ContentObserver](https://developer.android.com/reference/android/database/ContentObserver) to invalidate or rebuild an immutable snapshot on a handler thread. Publish it atomically, with a generation or synchronization strategy that prevents an in-flight old load from overwriting a newer value. Keep boot-time defaults and a bounded fallback for providers that do not notify as expected. In SystemUI, observe only the relevant switch URIs and unregister with the owning lifecycle where applicable.

In the activity, coalesce slider broadcasts and send only changed keys. `pushConfig()` currently sends every in-memory integer setting for each slider tick, and the receiver rewrites them individually. Flush the final value when dragging stops and before leaving the activity. Add a receiver acknowledgment so the UI distinguishes pending, saved, and unavailable-module states; a timeout should not say that a value was saved.

Validate burst edits, a final update followed immediately by activity exit, direct adb changes, delayed provider access at boot, and simultaneous receiver/audio-hook reads. Compare writes and Binder calls before/after.

## 6. Invalidate geometry and improve accessible controls

**Priority: medium — layout and usability.** `PanelNumber` keeps calibration for the lifetime of SystemUI, including across rotation/density/panel-mode changes. Its seekbar cache validates bounds but does not verify that the view still belongs to the current decor. Reset calibration for relevant configuration and display changes and verify current ancestry before reusing the cached slider. Retain stable calibration within one configuration so the original coordinate-drift problem does not return.

The custom `M3Switch` is a plain `View`; expose checkable/checked state and a switch class name through accessibility APIs, including state-change events. Associate each switch/slider with its label. The English edition adds spoken descriptions to the symbolic adjustment buttons; a further pass should raise 28dp stepper targets to at least the platform-recommended touch-target size while keeping the visual button compact, and check large fonts and narrow screens.

Validate TalkBack state announcements, keyboard navigation, 320dp width, larger text, rotation, and expanded/collapsed HyperOS panels.

## 7. Make builds and diagnostics reproducible

**Priority: lower — maintenance.** Keep the standalone build, but add dependency checksums, explicit release-signing configuration, and CI that builds on Windows and Linux. Quote generated javac argument-file entries for workspace paths containing spaces. Derive the footer version from package metadata rather than copying the manifest's version string into resources. Remove unused imports/helpers once their compatibility role has been checked.

For diagnostics, separate SystemUI's debug-setting lookup from `VolumeFineModule.config()`, which obtains a system context by reflecting `ActivityThread`; use the actual panel context for SystemUI settings. Cache reflected method handles that are still needed on repeated paths, and keep expected compatibility failures out of hot-path logs.

Build validation should include APK signature verification, resource/manifest inspection, Xposed metadata presence, launcher and themed-icon checks, and UI smoke tests. Add focused behavioral tests for restoration, input-unit conversion, sender authorization, and visibility-driven polling when implementing those changes. Real Xiaomi/HyperOS validation remains essential for system hooks.

## Changes already included in the English edition

- Centralized English Android strings and plurals, descriptive labels for controls, and stacked parameter headers on narrow screens or with larger text.
- Adaptive/round launcher icons, Android 13+ monochrome resources, and a matching settings-header mark.
- English developer documentation, build messages, and source comments; historical behavior claims clarified against the current implementation.
- Manifest-derived build versions, an explicit SDK platform default, correct Java classpath separators on Windows/Unix, a `LIBXPOSED` override, and output inside the repository's ignored `dist/` directory.
- LF rules for source/build files and ignored local toolchain dependencies.

The priority runtime changes above remain recommendations and are not part of this edition.

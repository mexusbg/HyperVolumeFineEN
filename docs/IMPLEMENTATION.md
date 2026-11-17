# Implementation and upstream findings

This is an English rendering of the original project's technical notes, reorganized for readability. Hardware measurements below were reported by upstream on Xiaomi 13 (`fuxi`) / HyperOS 4.0 / Android 17, on November 11, 2026. They have not been reproduced on that hardware for this fork. Where the notes and code differ, the discrepancy is identified in [OPTIMIZATIONS.md](OPTIMIZATIONS.md).

## Why stock low-volume steps are too large

On the device studied upstream, `ro.config.media_vol_steps=29` controls the media stream (`STREAM_MUSIC`).

| Layer | Range or increment |
|---|---|
| Stored setting, such as `volume_music_speaker` | 0–29 user-visible levels |
| `AudioService` internal `mIndexMax` | 0–290, or levels × 10 |
| Internal increment per volume-key press | ±10, one level |
| Index sent to audio policy | 0–29; volume-group `Index Max = 29` |
| Speaker curve in `/vendor/etc/audio_policy_volumes.xml` | 0–150, normalized to 29 levels |

There are therefore 29 nonzero user-visible levels, each spanning about 3.4% of the curve. Upstream reports a steep low-volume speaker curve: index 0 at −75dB, 1 at −73dB, 2 at −64.5dB, and 7 at −58dB. A low-volume step can span roughly 3–8dB, making adjacent settings sound too quiet or too loud.

The internal range of 290 alone does not provide ten times as many usable loudness levels. On the studied ROM, `mIndexStepFactor` divides the internal index back down; audio policy needs a larger maximum to expose more of the existing curve.

## Expanding the actual level count

During `AudioService` construction, the module intercepts `SystemProperties.getInt("ro.config.media_vol_steps")` and returns the configured level count (100 by default). It also patches selected entries in `MAX_STREAM_VOLUME`.

Upstream observed that `AudioService` then supplies 100 both to the framework's media maximum and to `setMaxVolumeIndexForGroup(4, 100)` in audio policy. Each step spans about 1% of the curve instead of 3.4%; upstream estimates roughly 1–2dB per step at low volume on its device. These are device-specific observations, not a universal loudness guarantee.

The vendor curve itself is unchanged, including its steepest low-volume segments.

## Volume keys and slider input

`VolumeStreamState#adjustIndex(int,int,String,boolean)` is hooked to control per-key increments. The current index is compared to `threshold * STEP_UNIT`, where `STEP_UNIT = 10`.

- Below the threshold, use the configured fine step (default 1, supported range 1–6).
- At or above the threshold, use the coarse step (default 4; the app exposes 1–8, while the hook accepts 1–10).
- Keep the original direction, clamp through the framework's normal path, and preserve other call arguments.

The original rationale was that increasing the maximum also made the framework calculate larger key increments, defeating fine low-volume control without this hook.

`VolumeStreamState#setIndex(int,int,String,boolean)` handles absolute input such as slider drags. The module rounds the index onto a grid, with snapping disabled when the configured grid is 1. A `ThreadLocal` flag prevents the nested key path from being snapped again; it is removed in a `finally` block.

The original README described a default grid of two user-visible levels. The code passes `cfg.snap` directly as an internal-index grid, however. Since one level equals ten internal indices, the implementation currently does not match that description. See the proposed fix and test cases in the review.

## Drawing the number inside the volume panel

Since upstream v1.5, `PanelNumber` hooks `WindowManagerImpl` and `WindowManagerGlobal` `addView` methods inside SystemUI. It identifies volume windows by type (2020 on the studied HyperOS build, 2014 on AOSP) or a title containing `VolumePanel`.

The number is a plain `TextView` inside the panel's own content container, without its own window or background plate. It is centered horizontally on the slider, below the three-dot icon. The text switches from white over glass to dark over white fill; hysteresis reduces color flicker when the fill crosses the text.

### Position calibration

Upstream observed that repeated panel shows caused the measured window coordinates of MIUI's `MiuiVolumeSeekBar` to drift right by a dozen or more pixels (for example 870 → 882 → 898). Continuously following those coordinates moved the number progressively out of alignment.

The v1.7 positioning strategy caches a one-time calibration after the layout stabilizes: right-edge offset, top offset, width, and height, converted to dp. Later shows use these cached coordinates instead of following the drift. Two matching measurements in the polling loop count as stable; one-shot layout and delayed callbacks also attempt positioning.

The number fades in once the slider is visible and fades out when it is hidden or begins exiting. Repositioning avoids interfering with the MIUI slide animation. Reused windows can rebuild their contents and remove the injected view; the loop reattaches it when necessary.

The original v1.8 notes describe immediate cached placement and a 240ms entrance fade. The current code places from cache immediately but waits for visible-slider detection, and `fadeIn()` uses 120ms. `fadeOut()` uses 110ms. The implementation is the source of truth for the current timings.

Calibration lives only in the SystemUI process and is repeated after a SystemUI restart. It is not currently invalidated for rotation, density changes, or an expanded panel; the review recommends addressing that.

### Removal of the old overlay

Since v2.3 there is no separate `system_server` overlay display or fallback overlay. Old keys `volume_fine_inpanel_ok` and `volume_fine_badge_dx/dy` were removed. SystemUI reads `volume_fine_enabled` and `volume_fine_show_number` with a one-second cache to control the embedded number.

## Configuration persistence

Third-party apps cannot write arbitrary custom keys into `Settings.System`; upstream encountered the platform exception `You cannot keep your settings in the secure settings`.

The current path is:

```text
SettingsActivity: in-memory values
    -> broadcast io.github.usama6878786.hypervolumefine.ACTION_CONFIG
    -> module receiver in system_server
    -> Settings.System
```

The activity reads settings through `Settings.System` and sends its integer settings as broadcast extras. The privileged module writes them and invalidates its cached configuration. Direct `adb shell settings put system ...` commands remain useful for development.

The code attempts sender-UID verification using reflection for `BroadcastReceiver#getSentFromUid()`, but **allows broadcasts when identity is unavailable**. The activity does not explicitly request identity sharing. Consequently this is not a complete authentication boundary; see the priority review finding.

The settings activity currently has no acknowledgment from the receiver. If the module is inactive, it can display edited in-memory values and a save toast without persisting them.

## Disabling and restoring stock levels

During startup, if `volume_fine_enabled=0`, the module calls `restoreStockVolumeSettings()` instead of applying the larger maximum. Upstream intended to scale stored volume values back from 100 to the stock 29 to prevent them being clamped to maximum after disabling.

The current implementation scans settings whose names start with `volume_`, then rescales integer values above 29 using `round(value * 29 / 100)`. This includes more than media-volume settings and can include configuration keys. It also leaves values at or below 29 unchanged and assumes a 100-level source maximum. Those are inherited correctness issues, documented with a proposed migration strategy in the review.

## Upstream hardware observations

The original README included these illustrative logs/results:

```text
module loaded, process=system
stream 3 volume steps: 15 -> 100
override ro.config.media_vol_steps -> 100
volume group 4 maxIndex=100          # Audio-policy maximum also becomes 100
updateIndexFactors stream=3 max=1000 # Internal range: 0–1000

# dumpsys audio: maximum changed from 29 to 100
- STREAM_MUSIC: Min: 0 Max: 100

# Key-path observations
cur=250 step 60 -> 10    # Fine range: replace framework step with one level
cur=290 step 60 -> 10
cur=300 step 60 -> 40    # Threshold reached: four levels
cur=340 step 60 -> 40
# Observed HAL sequence included 29 -> 28 -> 27 ... (one level at a time)
```

Some log strings in that historical example are not emitted by the current source. They are retained as upstream observations rather than promised output from this build.

## Settings screen

The Material 3 style interface uses framework widgets, programmatic layout, and custom drawables. It has no AppCompat or Material dependency and can be built without Gradle.

- Light palette: surface `#FEF7FF`, container `#F3EDF7`, primary `#6750A4`.
- Dark palette: surface `#141218`, container `#211F26`, primary `#D0BCFF`.
- Rounded 24dp cards, primary-container value chips, and tonal action buttons.
- Custom `M3Switch`: a 16dp off thumb with outlined track, a 24dp on thumb with solid primary track, and a 180ms animation.
- Slider track/thumb sizes of 4dp/20dp; system-bar and cutout insets accommodate edge-to-edge layouts.
- Current media volume appears in the status chip and precise-adjustment card. Its −/+ buttons use the standard volume-key adjustment path.
- Parameter sliders for total levels and fine range move in steps of ten; adjacent −/+ controls move one level at a time, allowing values such as 91.
- A pending level-count change highlights the reboot hint in purple. Save reminders are delayed 700ms after the last change and identical messages are suppressed for two seconds.

The English edition moves readable UI copy into `res/values/strings.xml`, supplies English plurals and spoken labels for symbolic adjustment buttons, and uses the new speaker/precision-slider icon in the header and launcher.

## Performance measures already present upstream

- Stop polling after approximately 600ms without an attached, shown host. A reused, still-shown host with a hidden slider is an unresolved case, described in the review.
- Read all `volume_fine_*` settings in one provider query, falling back to individual keys if it fails or returns no rows. The system-server configuration cache lasts 800ms.
- Cache parsed stream lists, slider view references, `AudioManager`, and maximum media volume.
- Reuse the current volume for text-color calculations rather than making a second volume IPC.
- Only call `setText` when the numeric value changes; only bring the number to the front when attaching it.
- Cache calibrated coordinates, while continuing lightweight visibility checks to drive fade-out.
- Remove one-shot global-layout listeners after invocation.

Upstream reported zero additional SystemUI CPU use after panel closure and no continuing log growth. This fork has not reproduced that hardware measurement, and the reused-host case should be profiled before relying on it.

## Hook reference

| Target | Purpose |
|---|---|
| `SystemProperties.getInt`, filtered to `ro.config.media_vol_steps` | Media maximum override |
| `AudioService` constructor | Patch stream maxima, restore stock settings when disabled, register configuration receiver |
| `VolumeStreamState#adjustIndex` | Fine/coarse key increments |
| `VolumeStreamState#setIndex` | Absolute-index snapping, bypassed on key path |
| `VolumeStreamState#updateIndexFactors` | Diagnostic logging only; no nonlinear mapping is implemented |
| `AudioSystemAdapter#setStreamVolumeIndexAS` | Diagnostic logging only |
| `WindowManagerImpl/Global#addView` in SystemUI | Find the panel and attach the number |

The original hook table mentioned an `AudioService#adjustStreamVolume` hook and a separate badge refresh path. Neither exists in the current source; the in-panel number is refreshed by `PanelNumber` polling.

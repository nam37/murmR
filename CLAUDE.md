# murmr

Android app that turns a phone into a push-to-talk voice keyboard: on-device speech-to-text on
the phone, text typed into a paired computer over Bluetooth HID. Product overview in README.md,
design decisions and roadmap in docs/architecture.md, Milestone 1 test protocol in
docs/testing.md.

## Layout

- `android/` is the Gradle project (Kotlin, Jetpack Compose, Material 3). Open this folder in
  Android Studio, not the repo root.
- `android/app/src/main/java/dev/murmr/app/`
  - `hid/` Bluetooth HID keyboard: report descriptor, US key map, `HidKeyboard`, `TextTyper`.
  - `stt/` `SttEngine` interface, `OfflinePolicy`, `AndroidSttEngine` (platform SpeechRecognizer
    owning the mic), `AudioSourceSttEngine` (experimental: the app records and streams audio to
    the recogniser via `EXTRA_AUDIO_SOURCE`, Android 13+), `PlatformRecognizer` (shared
    recogniser creation under the offline policy).
  - `transport/` `Transport` interface with `Capabilities` and `DeliveryResult`.
  - `service/` `MurmrService` foreground service owning HID, STT, transport, and the PTT state
    machine.
  - `diag/` `EventLog`: in-app ring buffer of link, phase, engine and timing events, copyable
    from Settings > Diagnostics. Field reports come without logcat; log anything a bug report
    would need here, not only to `Log`.
  - `settings/` `Settings` (app-wide, persisted in SharedPreferences) and `SettingsStore`,
    owned by `MurmrApp`, read by the service and the UI.
  - `macros/` per-computer keycaps: `Macro`/`MacroAction` (key chord, OS-resolved shortcut
    preset, text snippet, none), `HostOs`, `Keys` (HID usages and symbols), `MacroStore`
    (JSON in SharedPreferences keyed by the computer's Bluetooth address).
  - `ui/` `MainScreen` (the instrument layout) and theme.
  - `ui/instrument/` the skeuomorphic screen built from the approved artwork: `Materials`
    (chassis, glass panels), `TalkButton`, `Waveform`, `Chrome` (brand, status pill),
    `MacroKey` and `MacroCluster` (the four keycaps around the talk button), `MacroEditorSheet`
    (long-press assignment), `ConnectionSheet` (per-computer: pairing and OS profile),
    `SettingsSheet` (app-wide settings plus the temporary artwork-comparison switches),
    `Palette` (mockup colours).
  - `MainActivity.kt` permissions, service binding, Volume Down as PTT.
- `design/phone-mockup` is the approved visual target; `design/phone-mockup-assets` and
  `design/production-assets-v1` are the artwork sources copied into `res/drawable-nodpi`.

## Build and run

- Android Studio: open `android/`, Run on a physical phone. Emulators have no Bluetooth HID.
- CLI: `cd android && ./gradlew assembleDebug` (the wrapper is committed). Gradle 8.11 needs
  JDK 17 to 23; Android Studio's bundled JDK is newer, so the machine's
  `~/.gradle/gradle.properties` sets `org.gradle.java.home` to a Temurin 17 install. The SDK
  path lives in the gitignored `android/local.properties`.
- First end-to-end success 2026-09-16: sideloaded debug APK on a Pixel 11 Pro, paired with a
  Windows 11 PC, on-device STT, text typed over Bluetooth HID. Milestone 1 (50 dictations) has
  not been run yet, so reliability is still unmeasured.
- Before adding features, run docs/testing.md (50 consecutive dictations). That is Milestone 1.

## Constraints

- minSdk 28 (BluetoothHidDevice), targetSdk 35. Versions are in `gradle/libs.versions.toml`.
- The manifest `<queries>` block for `android.speech.RecognitionService` is required on
  Android 11+; without it no recogniser is visible. Do not remove it.
- `SpeechRecognizer` must be called on the main thread. The service runs there; keep it so.
- `OfflinePolicy.REQUIRED` is the default and the privacy promise. Never add a silent fallback
  to the network-backed default recogniser; PREFERRED must be an explicit user choice.
- Bluetooth calls need BLUETOOTH_CONNECT at runtime on Android 12+. `MainActivity` checks it
  before the service starts, which is why `HidKeyboard` suppresses the MissingPermission lint.
- Keep `BluetoothDevice` objects inside `hid/`. Service and UI use `HostDevice` (name, address).
- Service and UI depend on `Transport`, never on `HidKeyboard` or `TextTyper` directly. New
  transports implement `Transport` under `transport/` and declare their `Capabilities`.
- New STT engines implement `SttEngine` under `stt/`; the service should not need changes.
- Continuity within a hold belongs to the engine, not the service (see docs/architecture.md
  "Continuity lives in the engine"). `AndroidSttEngine` asks for an Android 13+ segmented
  session with punctuation, and stitches restarted sessions together when that is not honoured;
  it emits `Partial`s with the whole running transcript and exactly one `Final` per hold. A new
  engine must uphold that contract. Do not push session/restart logic back into the service.
- PTT rules live in `MurmrService` and docs/architecture.md "Reliability rules": nothing typed
  mid-hold, a capture tail after release (600 ms minimum, extended while partials arrive, 1.5 s
  cap) before stopping the engine, and the media stream muted for the hold to silence
  recogniser earcons. Keep them.
- Dictation timing (press-to-ready, release-to-stop, stop-to-final, release-to-typed) is logged
  under the `MurmrTiming` tag and shown as a line under the transcript after each dictation.
  It is the instrument for the clipped-speech investigation; do not remove it.
- "Erase last" is backspace-count erasure of exactly what was delivered, never an undo. It is
  invalidated by a new dictation, a disconnect, a host change, an erase, or the transcript
  auto-clear. The app cannot know what the computer did to the text in between; keep the name
  and the conservative rules.
- Settings split: app-wide settings (tail, offline policy, auto-clear, keep-awake) in the
  Settings sheet (footer button, plus a link from the connection sheet); per-computer settings
  (pairing, OS profile) in the connection sheet; key assignments via long-press on a keycap.
- Keycaps: shortcut presets are stored by name and resolved from the computer's `HostOs` at
  send time, never stored as chords, so changing the profile fixes every preset. The OS is
  never inferred from the Bluetooth identity; until set, presets show "?" and refuse to send.
  Keys are inert while listening, finishing, typing or erasing, so keystrokes never
  interleave with dictation; a 250 ms debounce makes a double tap one press. Any keycap send
  invalidates Erase last. Saving in the editor never sends.
- The talk button hit-tests as a circle (`clip(CircleShape)` before `pointerInput`) because the
  keycaps sit at the corners of the same 282 dp cluster. Keep that order.
- Gesture handlers on the talk button and keycaps are keyed on `Unit` and read `enabled`
  through `rememberUpdatedState` at press time. Keying `pointerInput` on `enabled` restarts the
  gesture when the Bluetooth link flaps mid-hold, which releases the press and kills the
  dictation. A finished dictation also waits up to 3 s for a dropped link to return before
  reporting "nothing typed".
- The service swaps engines from the `continuousCapture` setting only while idle (deferred to
  the next press otherwise). `AudioSourceSttEngine` is off by default and experimental until a
  phone confirms the on-device recogniser accepts a supplied stream; a rejected stream surfaces
  as an error, never a silent fallback. The button says "Hold to talk"; no other text repeats it.
- `TextTyper` reports exactly what was delivered (`DeliveryResult.delivered`); the UI shows that,
  not the recogniser's text. Preserve this so users can trust the phone display.
- `MurmrService` is START_NOT_STICKY on purpose (microphone FGS cannot restart from background).
- No host-side software in the direct Bluetooth mode. The companion transport is a roadmap
  item, not v0.
- **Visual target is the approved mockup** in `design/phone-mockup` (skeuomorphic: brushed
  blue-gray chassis, inset glass panels, teal metal push-to-talk button, reactive waveform).
  Use its artwork (`design/phone-mockup-assets/`) wherever it contributes to the finish:
  chassis as a crop-to-cover background, glass panels 9-sliced so edges stay native, button
  faces as images. Do not substitute procedural or vector surfaces unless a side-by-side
  comparison shows them at least as good. Drawing is for live layers only (waveform, glow).

## Decisions log

- 2026-09-16: Transport = Bluetooth HID keyboard (zero install on host). STT = Android on-device
  SpeechRecognizer first; sherpa-onnx or whisper.cpp later. PTT = on-screen hold button plus
  Volume Down in the foreground; the power button cannot be intercepted by apps.
- 2026-09-16: After review. Offline policy REQUIRED by default (no silent online fallback).
  One hold may span several recogniser sessions; type once on release. Caps Lock compensated
  from the host LED report. Delivery behind `Transport`. Streaming typing moved to the end of
  the roadmap. Milestone 1 = 50 consecutive dictations before new features.
- 2026-09-16: Companion app on the computer is planned, shared by Android and iPhone (iOS has
  no public HID-keyboard API). Images: first as uploaded URLs typed into the prompt for LLM
  apps, later as real attachments via the companion. LLM text cleanup will be opt-in with the
  verbatim transcript kept visible.
- 2026-09-16: Continuity moved into the STT engine (segmented session + restart fallback);
  release grace window and recogniser-tone muting added to the service; auto-punctuation on.
- 2026-09-16: Skeuomorphic mockup (`design/phone-mockup`) approved as the visual target. Ship
  the artwork; no procedural stand-ins without a side-by-side win.
- 2026-09-16: Instrument screen built from the artwork. Defaults are the mockup's own assets
  (chassis crop-to-cover, glass stretched per its stylesheet); the production-assets-v1
  candidates (mirrored metal tile, nine-sliced glass) are selectable in the connection sheet
  for on-phone comparison, not yet approved. Typing cursor advances from real HID send progress
  (`Transport.sendText(onProgress)`), never elapsed time. Haptic tick on mic-open, double tick
  on delivery. Brief SENT phase after typing.
- 2026-09-17: Dictation is disabled while no computer is connected, matching the mockup. The
  service enforces it in `pttDown` so Volume Down cannot bypass the disabled button.
- 2026-09-17: Batch 1 after macOS test. Timing instrumentation (logcat + on-screen line).
  Capture tail is now a fixed 600 ms minimum, not partial-cadence-based, because partial
  results are not evidence of silence. Portrait locked. Screen kept awake while connected or
  mid-dictation. Logo mark and wordmark both off-white, 20% smaller. Both glass panels are
  nine-sliced; the original panel's boundaries (22,162,1400,1540 / 98,238,750,890) were
  verified against its pixels. The mockup's 125% stretch rule is gone: it cropped the tall
  panel's rims and squashed the strip's.
- 2026-09-17: Batch 2. Persisted app settings with a Settings sheet (capture tail 400-1000 ms,
  on-device-only vs allow-online recognition, transcript auto-clear 15/30/60/never, keep-awake
  mode). Transcript auto-clears after a dictation (default 30 s, countdown restarts on touch,
  never mid-dictation or over an error). "Erase last" sends one backspace per delivered
  character, shown with reverse progress, available until invalidated.
- 2026-09-17: Batch 3. Macro cluster from the updated mockup: four keycaps (approved
  macro-idle/pressed masters) at the corners of a 282 dp cluster around the talk button, default
  Enter / Tab / Esc / Paste. Per-computer OS profile (Windows, macOS, Linux) in the connection
  sheet. Long-press opens the editor: WYSIWYG preview, caption, Key mode (common keys and
  presets as keycaps, any key plus modifier toggles) or Text mode (snippet, Enter after,
  delivered-text preview), Clear / Cancel / Save. Stored per computer as JSON.
- 2026-09-17: After the second phone test. Auto-clear default 15 s and the clear plays as the
  reverse of the typing reveal. Only the button says "Hold to talk". Transcript pins to the
  bottom while text arrives. Recogniser tones muted on the system stream as well. The pause-
  stop (the platform recogniser's own endpointing, ~1-2 s) gets a real fix behind an
  experimental setting: `AudioSourceSttEngine` streams the app's own microphone capture to the
  recogniser so the session ends only when the pipe closes after the tail; no restarts, no
  earcons, and the audio path a pre-roll buffer needs later.
- 2026-09-17: Field report of the Bluetooth link flapping mid-hold (PC connect/disconnect
  sounds, button toggling, dictation cut). Cause unknown. Response: in-app event log with copy
  (Settings > Diagnostics), every HID state transition logged, holds now survive a link drop
  (gesture no longer keyed on enabled; final waits up to 3 s for the link), and the
  system-stream tone mute from the previous build reverted as the only audio change that
  coincided with the report.

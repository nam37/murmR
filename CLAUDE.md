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
  - `stt/` `SttEngine` interface, `OfflinePolicy`, `AndroidSttEngine` (platform SpeechRecognizer).
  - `transport/` `Transport` interface with `Capabilities` and `DeliveryResult`.
  - `service/` `MurmrService` foreground service owning HID, STT, transport, and the PTT state
    machine.
  - `ui/` Compose screen and theme.
  - `MainActivity.kt` permissions, service binding, Volume Down as PTT.

## Build and run

- Android Studio: open `android/`, Run on a physical phone. Emulators have no Bluetooth HID.
- CLI: `cd android && ./gradlew installDebug` once the wrapper exists (see README).
- The scaffold was written without an SDK on the machine and has never been compiled. Treat the
  first build as validation and expect small fixes.
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
- PTT state machine rules live in `MurmrService` and docs/architecture.md "Reliability rules":
  nothing is typed mid-hold, text accumulates across recogniser sessions, 5 s finishing
  timeout, 700 ms fast-fail guard. Keep them when editing the service.
- `TextTyper` reports exactly what was delivered (`DeliveryResult.delivered`); the UI shows that,
  not the recogniser's text. Preserve this so users can trust the phone display.
- `MurmrService` is START_NOT_STICKY on purpose (microphone FGS cannot restart from background).
- No host-side software in the direct Bluetooth mode. The companion transport is a roadmap
  item, not v0.

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

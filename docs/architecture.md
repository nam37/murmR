# murmr architecture

Last updated 2026-09-16.

## Goal

A phone-as-microphone voice keyboard. Hold a button on an Android phone, speak, release, and
the transcribed text is typed into whatever has keyboard focus on a nearby computer. The same
feel as Whisper Flow, but the microphone and the speech model live on the phone and the
computer needs nothing installed. A primary use is dictating to LLM apps on the computer.

## Pipeline

1. **Trigger:** push-to-talk press and release.
2. **Capture and STT:** microphone audio to text, on the phone.
3. **Transport:** text to the computer.
4. **Injection:** text becomes keystrokes in the focused field.

With Bluetooth HID, steps 3 and 4 collapse into one: the phone *is* the keyboard.

## Decisions

### Transport: Bluetooth HID keyboard first

| Option | Host software | Unicode | Notes |
|---|---|---|---|
| **Bluetooth HID keyboard** (`BluetoothHidDevice`, Android 9+) | none | US-layout ASCII, without tricks | Computer sees a real keyboard. Works on any OS, on lock screens, even in BIOS. Some OEM phones block the HID device role. |
| Wi-Fi/LAN companion (WebSocket + host agent that injects keystrokes) | required | full (paste or per-OS APIs) | Needs a host agent per OS, same network, device discovery. Only path for iPhone and for images. |
| Bluetooth RFCOMM + host agent | required | full | As above, without needing Wi-Fi. |
| Cloud relay | required | full | Off-device text, latency, accounts. Ruled out. |

HID wins for v0 because it matches "acts as a keyboard" literally and needs zero host setup.

**How HID typing works.** The phone registers a keyboard report descriptor with
`BluetoothHidDevice.registerApp`. Each character becomes a HID *usage* (a key position) plus
modifiers, sent as an 8-byte input report followed by an all-released report. `TextTyper` paces
reports at about 8 ms each so hosts do not drop keys.

**Keyboard layouts.** HID sends key positions, so the host's layout decides the character.
`KeyMap` is US/ANSI. Other layouts need their own tables and a setting. Alternatives for unicode
are OS-specific entry tricks (Windows Alt codes on numpad usages, Linux Ctrl+Shift+U) or the
companion transport.

**Pairing quirk.** A computer that was paired with the phone before murmr registered its
keyboard may not know about the HID service. Removing and re-adding the pairing fixes it.

**Delivery is behind an interface.** `Transport` (`transport/Transport.kt`) exposes
`capabilities` (text, images, clipboard paste), `isReady`, and `sendText`. `TextTyper` is the
HID implementation and claims only text. The service and UI never touch HID directly, so the
companion transport slots in without changing the state machine, and the UI can hide features
the active transport cannot deliver.

### Companion transport (planned; shared by Android and iPhone)

Two experiences, both kept:

- **Direct Bluetooth.** Dictation only, nothing installed on the computer. Android only.
- **Companion.** A small app on the computer receiving over Wi-Fi or Bluetooth RFCOMM. Adds
  unicode and non-US layouts, clipboard-based insertion, images, and is the only path for
  iPhone. Also faster than typing character by character.

For images the companion distinguishes **send to computer** (a file lands and is always kept)
from **paste into the active app** (works only where the destination accepts images). A failed
paste must never lose the image.

### Images before the companion: links in the text stream

The keyboard link can carry a URL but not an image. Because the primary use is dictating to
LLM apps, an image can be uploaded from the phone to a server (to be defined: self-hosted or a
service) and its URL typed into the prompt alongside the dictated text:

> What is causing this error? Screenshot: https://…

Rules:

- The uploader is separate from delivery. It returns a URL; murmr includes it in the text.
  Replacing the server later does not touch Bluetooth typing.
- Whether the receiving app fetches URLs varies by app and must be tested per app before this
  becomes a default flow. A link in a prompt is not an attachment.
- Links are unguessable and expiring, with a way to delete. Uploading is an explicit exception
  to the on-device privacy promise and needs a per-image confirmation.

### Speech-to-text: Android on-device SpeechRecognizer first

| Engine | On-device | Streaming partials | Effort | Notes |
|---|---|---|---|---|
| **Android `SpeechRecognizer`** via `createOnDeviceSpeechRecognizer` (Android 12+) | yes, with a language pack | yes | low | Google/OEM engine. Good on Pixel. |
| sherpa-onnx (Zipformer, Whisper, Parakeet ONNX models) | yes | yes | medium | Fully under our control, good streaming models, Android and iOS bindings. Candidate for the "own engine" phase. |
| whisper.cpp via JNI | yes | no, chunked | medium | Accurate; tiny/base models are fast enough for short utterances. Android and iOS. |
| Cloud APIs | no | yes | low | Ruled out for privacy and latency. |

The engine is behind `SttEngine` (start, stop, cancel; events Ready, Partial, Final, Error) and
owns continuity within a hold (see "Continuity lives in the engine"), so swapping it is local to
`stt/`. An own model would be a first-run download with a size shown, not baked into every
install. It gets adopted on measured accuracy, battery, and release-to-text latency, not on
model size.

**Offline policy.** `OfflinePolicy.REQUIRED` is the default. On Android 12+ the engine uses
`createOnDeviceSpeechRecognizer` or fails with a message telling the user to install the
language pack or allow online recognition. It never silently falls back to the default engine,
because `EXTRA_PREFER_OFFLINE` is a hint Android may ignore. Android 9 to 11 has no on-device
guarantee, so REQUIRED fails there until the user chooses PREFERRED. The choice will live in
settings; today it is a constructor argument in `MurmrService`.

### Text post-processing (planned)

Two modes:

- **Verbatim** (default): the recogniser's transcript, untouched.
- **Clean up**: punctuation, filler removal, spoken corrections ("Tuesday, sorry, Thursday" to
  "Thursday"), while preserving meaning.

Simple spoken commands ("new line", "delete that") start as deterministic rules. A small
on-device LLM can do cleanup later. Risks: a helpful-looking rewrite changes a name, number, or
intention. So the original transcript stays visible, cleanup is opt-in, and the model never
executes actions such as "send". Text-to-speech readback is low priority; system TTS if ever.

### iPhone

iOS has no public API for acting as a Bluetooth HID keyboard; Core Bluetooth peripheral mode
exposes GATT services, not a keyboard. iPhone therefore gets the companion transport. Considered
and parked: a Bluetooth-to-USB keyboard dongle (hardware, InputStick-style; still layout-bound)
and a receiving web page (copy/paste only). Speech on iOS: SpeechAnalyzer/SpeechTranscriber
(iOS 26) on-device, with cleanup via on-device Foundation Models where available.

### Push-to-talk trigger

- **Power/lock button:** apps cannot intercept it on Android. Ruled out.
- **On-screen hold button:** always works. Implemented.
- **Volume Down:** interceptable while our activity is in front. Implemented as hardware PTT.
- **Quick Settings tile or notification action:** toggle-style rather than hold. Roadmap.
- **Bluetooth headset or media button:** a `MediaSession` can receive play/pause events.
  Roadmap.
- **Accessibility service for a global volume-key hold:** possible but heavy-handed. Only if
  users ask for it.

### Process model

`MurmrService` is a foreground service (`connectedDevice|microphone`) that owns `HidKeyboard`,
the `SttEngine`, the `Transport`, and the push-to-talk state machine:

```
IDLE --pttDown--> LISTENING --pttUp--> FINISHING --Final--> TYPING --done--> IDLE
                                          (grace)  |
                                                   +-- Error (nothing captured) --> IDLE
```

The activity binds to the service and renders `UiState`. Everything runs on the main thread:
`SpeechRecognizer` requires it and the Bluetooth callbacks arrive there too.

The service is `START_NOT_STICKY`. A microphone-type foreground service can only be started
from the foreground on Android 14+, so a system-initiated restart would crash.

### Continuity lives in the engine

One hold is one dictation even though the platform recogniser wants to stop at every pause. The
`SttEngine` owns this, not the service, so a future engine (sherpa-onnx, whisper.cpp) gets it
for free and the service does not change:

- **Android 13+ segmented session.** The engine asks for a session that survives pauses and
  returns each phrase through `onSegmentResults`, plus automatic punctuation and capitalisation
  (`EXTRA_ENABLE_FORMATTING`). When honoured, one hold is genuinely one session: no mid-hold
  restarts, no earcons between phrases, sentence-aware capitals.
- **Restart fallback.** When segmented mode is not honoured (older engines, or the extra is
  ignored), the engine restarts the recogniser after each result and stitches the phrases into
  one running transcript. A pause error within 700 ms is not restarted (tight-loop guard), and
  restarts are capped. This is invisible to the service: either way it sees `Partial`s and one
  `Final`.
- **Stop timeout.** If no final arrives within 4 s of `stop()`, the engine forces one from what
  it has, so a hold can never hang.

### Reliability rules

- **Capture tail.** On release the recogniser keeps capturing for at least 600 ms, then
  continues while partial results are still arriving, up to 1.5 s. The minimum is fixed
  because a gap between partial results is not evidence of acoustic silence; Android does not
  guarantee their cadence. The button shows "Finishing" during the tail.
- **Timing is measured, not guessed.** Each dictation logs press-to-ready, release-to-stop,
  stop-to-final and release-to-typed under the `MurmrTiming` tag and shows them under the
  transcript, so clipped speech can be attributed to startup loss, an early stop, or slow
  results without a debugger attached.
- **Nothing is typed mid-hold.** The engine emits one `Final` on stop; that is what is typed.
- **Recogniser tones muted.** The media stream is muted for the duration of a hold, silencing
  the platform's start/stop earcons and any restart click. Best effort, and it also mutes media
  playback for that moment.
- **Bluetooth follows the adapter.** Off at start or turned off later: the keyboard waits and
  re-registers when Bluetooth comes back.
- **Caps Lock.** The host reports LED state through the keyboard output report. While Caps
  Lock is on, letters are sent with Shift inverted so the case on the computer matches.
- **Fidelity.** The phone shows exactly the delivered text. Curly quotes and dashes are
  straightened, accents folded to the base letter (é to e) and flagged, and anything still
  without a US key is dropped and listed.
- **Streaming typing is last on the roadmap.** Revising already-typed text with backspaces
  breaks as soon as the cursor moves or the app changes. Reliable delivery after release is the
  product.

### Screen

The screen is the approved skeuomorphic mockup (`design/phone-mockup`) built from its artwork,
never from drawings of it (CLAUDE.md, "Visual target"). Layout, spacing, type and colour follow
the mockup's stylesheet values; `ui/instrument/Palette.kt` mirrors it.

- **Chassis**: the mockup image, crop-to-cover. Brushed grain is uniform, so cropping is
  invisible and nothing is ever stretched.
- **Glass panels**: the mockup image, nine-sliced with boundaries verified against its pixels
  (source 22,162,1400,1540 by 98,238,750,890; 28 dp corners), so the rim and corners keep their
  shape at any panel height. The mockup's own stretch rule was tried first and rejected on the
  phone: it cropped the tall panel's rims and squashed the strip's.
- **Talk button**: the two approved masters (idle, lit) cross-fading; mic glyph, ready lamp and
  label are live layers on top, as the asset pack specifies.
- **Live layers, drawn**: the waveform (fed by the recogniser's level callback) and the glows.
  These are the only things the artwork cannot contain.
- **Comparison switches**: the connection sheet can swap in the production-assets-v1
  candidates (mirrored-tiled metal with the lighting overlay; nine-sliced blank glass with the
  pack's slice coordinates). They ship for on-phone side-by-side judgement, not as defaults.
- **Delivery feedback**: while typing, the transcript shows delivered text, a caret, and pending
  text. The caret advances from actual keystrokes sent, never from a timer. A short "Typed"
  state follows, with a double haptic tick; a single tick marks the microphone opening.

### Privacy

With the default offline policy, audio never leaves the phone. Text travels only over the
Bluetooth link to the paired computer. The app declares no network permission. Image upload,
when it arrives, is the one explicit exception and is confirmed per image.

## Milestone 1

One phone and one computer complete 50 consecutive dictations, including pauses, quick taps,
disconnects, and app switching, with the delivered text matching the phone and release-to-text
latency measured. Protocol in [testing.md](testing.md). This comes before any new engine or
feature: it is the evidence the product works.

## Roadmap

1. **Make it run.** Done for Pixel 11 Pro to Windows 11 (2026-09-16). macOS untested. Next:
   Milestone 1.
2. **Usability.** Settings (offline policy, trailing space, language, key delay),
   auto-reconnect to the last host, haptics on press and release.
3. **Image links in the text stream.** Upload from the phone, type the URL with the prompt.
4. **Companion transport.** Shared by Android and iPhone: unicode, layouts, clipboard paste,
   images (send and paste).
5. **iPhone app** on the companion transport.
6. **Own STT engine.** sherpa-onnx or whisper.cpp with a first-run model download.
7. **Text post-processing.** Rules for commands, then optional on-device LLM cleanup.
8. **More triggers.** Quick Settings tile, headset button, Wear OS.
9. **Streaming typing.** Only if Milestone 1 shows latency is the main complaint.

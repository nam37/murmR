# Milestone 1: 50 consecutive dictations

The first thing murmr has to prove is that hold, speak, release reliably puts the right text
on the computer. This protocol produces that evidence. Run it before adding engines or features.

## Setup

Record once per run:

- Phone model and Android version; offline language pack installed (yes/no)
- Computer OS and version; keyboard layout set to US
- Target app: a plain text editor with autocorrect and auto-capitalisation off
  (Notepad on Windows, TextEdit in plain-text mode on macOS, gedit or Kate on Linux)
- murmr build (git commit)

## Script

50 dictations in one sitting, without restarting the app or re-pairing. Cover each case:

| # | Case | What it exercises |
|---|---|---|
| 1-10 | Short phrases, 2 to 5 words | baseline |
| 11-15 | Long passages, 30+ words | recogniser session length, key pacing |
| 16-20 | A pause of 3+ seconds mid-sentence with the button held | continuity across pauses: no click, no gap, no mid-sentence capital |
| 21-25 | Numbers, punctuation, a capitalised name, a quoted phrase | key map, Shift handling, auto-punctuation |
| 21a | A sentence that should end with a question mark | Android 13+ formatting adds punctuation |
| 21b | Trail off a word right as you release the button | release grace window keeps the last word |
| 26-28 | Quick taps under 300 ms with no speech | empty-result handling, no stuck phase |
| 29-33 | Back-to-back dictations with under 1 s between | state machine re-entry |
| 34-36 | Caps Lock on at the computer | LED report compensation |
| 37-39 | Words with accents ("café", "naïve") | folding and reporting |
| 40-42 | Bluetooth off and on at the phone, then dictate | adapter recovery |
| 43-45 | Disconnect from the computer's side, reconnect, dictate | host reconnect |
| 46-47 | Switch to another app on the phone and back, then dictate | service survival, rebinding |
| 48-49 | Screen off and on, then dictate | foreground service, microphone access |
| 50 | Volume Down as the trigger | hardware PTT path |
| 50a | Dictate, then Erase last; dictate again | backspace-count erasure leaves the field exactly as before |
| 50b | Dictate, click elsewhere on the computer, then Erase last | the documented limit: erasure hits the wrong place |
| 50c | Dictate and wait past the auto-clear time | phone clears; computer text untouched; Erase last gone |
| 50d | Set the computer's OS, dictate, tap Enter keycap | shortcut profile; Enter submits the field |
| 50e | Copy something on the computer, tap Paste keycap | preset resolves to Ctrl+V or ⌘V per profile |
| 50f | Double-tap Enter quickly | debounce: exactly one Enter |
| 50g | Long-press a keycap, assign a text snippet with Enter after, tap it | editor, text macro, trailing Enter |
| 50h | Tap a keycap mid-hold | inert: no keystroke interleaves with dictation |

## Record per dictation

| # | Said | Phone shows (delivered text) | Computer shows | Release-to-text ms | Pass | Notes |
|---|---|---|---|---|---|---|

Release-to-text is the time from letting go to the last character appearing. After each
dictation the phone shows a timing line under the transcript: press-to-ready, the capture tail
after release, how long the final result took after the stop, and release-to-typed. The same
numbers go to logcat under the `MurmrTiming` tag. Record the line; it tells clipped-start
(long ready time) from clipped-end (short tail or slow final) without a screen recording.

## Pass criteria

- Computer text equals the phone's delivered text for 50 of 50.
- No dropped, duplicated, or reordered characters.
- Median release-to-text under 1.5 s; no single dictation over 5 s.
- No crash, no phase stuck outside IDLE, and recovery after every disconnect without a reinstall
  or re-pair.

## Reading failures

- Still hearing a click or chime between phrases mid-hold: tone muting failed (check the
  `MurmrService` log for "could not mute"), or the earcon is on a stream other than media.
- Mid-sentence capitals or no punctuation: the Android 13+ segmented session and formatting
  are not honoured on this engine; the restart fallback is in use. Check the `AndroidSttEngine`
  log for "segmented session not honoured".
- Last word clipped at release: raise `GRACE_QUIET_MS`/`GRACE_MAX_MS` in `MurmrService`.
- Wrong case only with Caps Lock on: the LED report is not arriving. Check the `HidKeyboard`
  log for "host caps lock".
- Missing characters in long passages: raise `keyDelayMs` in `TextTyper`.
- Text typed mid-hold or lost after a pause: session handling in `MurmrService`.
- Stuck in FINISHING: the finishing timeout is not firing; check `pttUp`.
- Nothing after a Bluetooth toggle: the adapter receiver in `HidKeyboard`.

## Runs

### 2026-09-16, first end-to-end run (not a full protocol run)

- Phone: Pixel 11 Pro. Debug APK sideloaded, not installed over USB.
- Computer: Windows 11 (NAM-RYZEN), paired from the computer's Bluetooth settings.
- Build: commit 0d18059.
- Result: keyboard registered and connected on the first attempt; on-device recognition
  worked; three consecutive holds typed correctly into a desktop app's text field. No latency
  measured (no USB debugging, so no logcat).

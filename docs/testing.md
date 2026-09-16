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
| 16-20 | A pause of 3+ seconds mid-sentence with the button held | segment accumulation across sessions |
| 21-25 | Numbers, punctuation, a capitalised name, a quoted phrase | key map, Shift handling |
| 26-28 | Quick taps under 300 ms with no speech | empty-result handling, no stuck phase |
| 29-33 | Back-to-back dictations with under 1 s between | state machine re-entry |
| 34-36 | Caps Lock on at the computer | LED report compensation |
| 37-39 | Words with accents ("café", "naïve") | folding and reporting |
| 40-42 | Bluetooth off and on at the phone, then dictate | adapter recovery |
| 43-45 | Disconnect from the computer's side, reconnect, dictate | host reconnect |
| 46-47 | Switch to another app on the phone and back, then dictate | service survival, rebinding |
| 48-49 | Screen off and on, then dictate | foreground service, microphone access |
| 50 | Volume Down as the trigger | hardware PTT path |

## Record per dictation

| # | Said | Phone shows (delivered text) | Computer shows | Release-to-text ms | Pass | Notes |
|---|---|---|---|---|---|---|

Release-to-text is the time from letting go to the last character appearing. Read it from
logcat (`MurmrService: release-to-typed N ms`) or from a screen recording of both devices.

## Pass criteria

- Computer text equals the phone's delivered text for 50 of 50.
- No dropped, duplicated, or reordered characters.
- Median release-to-text under 1.5 s; no single dictation over 5 s.
- No crash, no phase stuck outside IDLE, and recovery after every disconnect without a reinstall
  or re-pair.

## Reading failures

- Wrong case only with Caps Lock on: the LED report is not arriving. Check the `HidKeyboard`
  log for "host caps lock".
- Missing characters in long passages: raise `keyDelayMs` in `TextTyper`.
- Text typed mid-hold or lost after a pause: session handling in `MurmrService`.
- Stuck in FINISHING: the finishing timeout is not firing; check `pttUp`.
- Nothing after a Bluetooth toggle: the adapter receiver in `HidKeyboard`.

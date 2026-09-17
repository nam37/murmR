# murmr interactive phone mockup

Browser implementation of skeuomorphic concept 3: brushed blue-gray chassis, inset glass displays, teal metal push-to-talk control, and reactive waveform.

## Run

From this directory:

```powershell
npm ci
npm run dev -- --host 127.0.0.1 --port 4173
```

Open http://127.0.0.1:4173/. Build with `npm run build`; static browser output is in `dist/client` and must be served over HTTP. The device picker previews the same interface inside phone shells.

## Try it

- Hold the round button or Space to animate sample input and reveal the demo transcript. Release to show finishing and delivery feedback.
- Escape cancels and restores the previous transcript. Enter works when the talk button is focused.
- Click the computer badge to disconnect/reconnect or choose input mode.
- Optional microphone input requests browser permission, then visualizes local microphone amplitude while held. Release closes the microphone stream. Audio is not uploaded, recorded, or transcribed.

Computer connection, transcription, and delivery are simulated. This prototype does not send Bluetooth keyboard events. Volume Down is an Android-only shortcut; Space is the browser equivalent.

## Files

- `src/Prototype.tsx`: state, interactions, microphone analyser, waveform drawing.
- `src/prototype.css`: app styling.
- `public/assets/murmr`: generated chassis, glass, idle/active button artwork, and existing brand logo.
- `qa`: browser screenshots; `design-qa.md`: validation notes.

The mobile preview runtime is preserved from the bundled template. Android application files were not changed.

Revision: transcript comes first, with status inside its glass panel and a compact waveform below. The microphone remains, with a subtle ready light inside its capsule. Button text stays fixed. The demo sending cursor is explicitly simulated; Android must drive progress from transport callbacks. Optional browser vibration uses one tick on input readiness and two after simulated sending completes; desktop browsers may not vibrate.

Macro revision: four square artwork keys surround the microphone. Sample assignments are Enter, Tab, Esc and Paste; taps only show simulated feedback. Art and layout notes: ../phone-mockup-assets/macro-buttons.md.

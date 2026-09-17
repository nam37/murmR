# murmr artwork asset pack v1

New empty material candidates, plus the approved original button masters. The running phone mockup and Android app have not been switched to these assets.

## Contents

| File | Actual dimensions | Use |
| --- | --- | --- |
| metal-tile.png | 1254 x 1254 RGB | Uniform blue-gray brushed grain; use mirrored tiling |
| glass-empty.png | 1448 x 1086 RGBA | Empty painted recessed panel; nine-slice source |
| lighting-overlay.png | 1024 x 1536 RGBA | Independent soft lighting; start at 10-18% layer opacity |
| talk-idle.png | 1254 x 1254 RGBA | Approved idle button, copied without modification |
| talk-active.png | 1254 x 1254 RGBA | Approved active button, copied without modification |
| manifest.json | — | Dimensions, alpha extrema, visible bounds |
| index.html | — | Interactive material comparison and nine-slice proof |

## Preview

From the repository root run:

```powershell
python -m http.server 4174 --bind 127.0.0.1 --directory design
```

Open http://127.0.0.1:4174/production-assets-v1/. Adjust the lighting slider. The page compares original and new materials, and renders the same new glass asset at two panel heights with preserved corner geometry.

## Implementation handoff

**Metal:** preserve grain scale independent of screen size. The generated texture is uniform but is NOT guaranteed seamless with ordinary repeat. Edge inspection found an average horizontal boundary RGB difference around 9-10 versus 3 between adjacent columns. The preview uses mirrored addressing (alternating flipped tiles), which avoids a hard boundary. Tile size in the preview is 220 logical pixels; tune on the phone. Mirroring can reveal symmetric grain, so inspect before shipping.

**Glass:** ordinary PNG with alpha, NOT an Android .9.png resource. Nine-slice source coordinates used in the preview: X = [8, 148, 1300, 1440], Y = [88, 218, 860, 990]. These exclude most transparent outer padding. Destination corner region is 28 logical pixels wide by 26 high. Source right/bottom boundaries are exclusive. Keep at least 56 x 52 logical pixels for this mapping. The 64px waveform strip and larger transcript panel were visually inspected. The broad interior is deliberately empty; waveform/grid/text remain separate. If converting to Android nine-patch, add proper stretch/content metadata and validate the compiled result; do not rename this file to .9.png.

**Lighting:** actual alpha range 0-220. Composite gently above the metal and below controls; do not tile it. It contains broad soft light, not grain. At full opacity it is too strong for the approved finish. This is an optional candidate, not a required replacement for existing lighting.

**Buttons:** preserved byte-for-byte from ../phone-mockup-assets. Already blank with transparent exterior, and enough pixels for a 250dp control at up to 4x density. Both canvases match; visible bounds differ by roughly one source pixel. Use the same destination rectangle for each. Add microphone, ready lamp, and label independently. No resampling or regenerated button variant was introduced.

## Validation and limitations

- Inspected the source images and generated outputs visually.
- Verified actual image dimensions and transparency with Pillow (read-only inspection).
- Inspected mirrored metal and nine-slice panel rendering in the browser.
- Requested larger outputs, but the generator returned the dimensions listed above. Files are not advertised as 2K and were not artificially upscaled.
- New panel has a slightly brighter blue-gray outer rim than the original. Treat it as a candidate for on-phone comparison, not automatically approved replacement artwork.
- Final Android density, device-size testing, packaging and user visual approval remain for the app port.

## Provenance

Generated using built-in imagegen with chassis.png and glass-panel.png from ../phone-mockup-assets as references. Original generations are retained under the Codex generated_images directory. Project copies here are the durable handoff.

Prompts requested: (1) evenly lit, fine horizontal blue-gray brushed grain without frame/vignette; (2) original painted glass rim and recess, uniform blank smoked interior without reflection stripe, with exterior alpha; (3) translucent broad cool upper-left lighting and subtle edge/bottom shading, without grain or opaque backing. Existing button masters were retained to protect their approved finish.

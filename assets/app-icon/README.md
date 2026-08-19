# App icon source

Vector source for LinkToMac's app icon — a phone (Android, green) layered in front of a laptop
(Mac, white/dark) as flat solid silhouettes, no sync badge. Comes in both a dark (black
background, white laptop) and light (light-gray background, dark laptop) theme — the phone stays
Android-brand green (`#3DDC84`) in both, only the laptop's color inverts for contrast against the
background.

**The dark theme is what's currently wired into both apps** (`desktop-app/src-tauri/icons/` and
`android-app/app/src/main/res/mipmap-*`). The light theme is exported here as an alternate asset
set (`preview-light-macos.png` etc.) — swap it in with the regeneration steps below if wanted.

Each theme has 4 files:

- `icon-<theme>-macos.svg` — the desktop/Tauri icon. Content sits inset within the 1024×1024
  canvas (~824px squircle, ~100px margin) with a drop shadow, matching how native macOS Big
  Sur+ icons are actually authored — a full-bleed square looks visibly "off" in the Dock next to
  real apps.
- `icon-<theme>-android-legacy.svg` — full-bleed edge-to-edge version (no inset/shadow) for
  pre-API26 Android launchers, which apply their own masking.
- `icon-<theme>-android-legacy-round.svg` — same, circle-clipped, for `ic_launcher_round`.
- `icon-<theme>-android-adaptive-foreground.svg` — glyph only, transparent background,
  scaled/recentered to fit inside adaptive-icon launchers' safe-zone circle (~58% of canvas
  diameter, more conservative than Android's ~61% minimum) so it isn't clipped by
  circle/squircle/rounded-square masks. The phone's home-indicator "cutout" pill is filled with
  the theme's background color rather than true transparency, so it only looks right paired with
  that theme's background layer — `android-app/app/src/main/res/drawable/ic_launcher_background.xml`
  (`#0A0A0A` dark / `#F2F2F2` light).

Earlier iterations included a two-arrow sync badge on the phone's corner. It's been dropped for a
simpler two-shape composition; if it comes back, use SVG `marker-end` with
`orient="auto-start-reverse"` for the arrowheads rather than hand-computed triangles — the latter
looked plausible in the math but rendered visibly crossed/misaligned.

## Regenerating

Requires `rsvg-convert` (`brew install librsvg`).

**Desktop (macOS/Windows/Linux):** rasterize `icon-<theme>-macos.svg` to a 1024×1024 PNG, then
let Tauri's own generator build every size/format it needs:

```bash
rsvg-convert -w 1024 -h 1024 icon-dark-macos.svg -o /tmp/icon-master.png
cd desktop-app && npx tauri icon /tmp/icon-master.png
```

That also writes `icons/android/` and `icons/ios/` subfolders — delete those, they're for
Tauri-mobile targets this project doesn't use (the real Android app is the separate native
project in `android-app/`, not a Tauri build target).

**Android:** rasterize the three `icon-<theme>-android-*.svg` files into each `mipmap-<density>/`
at:

| density | legacy (ic_launcher / _round) | adaptive foreground |
|---|---|---|
| mdpi | 48px | 108px |
| hdpi | 72px | 162px |
| xhdpi | 96px | 216px |
| xxhdpi | 144px | 324px |
| xxxhdpi | 192px | 432px |

If switching themes, also update the color in
`android-app/app/src/main/res/drawable/ic_launcher_background.xml` (`#0A0A0A` dark / `#F2F2F2`
light).

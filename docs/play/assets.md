# Graphic assets for the Play Store listing

> **2026-08-26: the assets exist now.** Everything below the rules table used
> to describe work to do; the produced, upload-ready files live in
> [`assets/`](assets/):
>
> | File | Slot | Spec check |
> |---|---|---|
> | `assets/icon-512.png` | App icon | 512×512 RGB PNG, rendered from the launcher vectors |
> | `assets/feature-graphic.png` | Feature graphic (en-US) | 1024×500 RGB PNG |
> | `assets/feature-graphic-fr.png` | Feature graphic (fr-FR) | 1024×500 RGB PNG |
> | `assets/screenshots/en/01–08*.png` | Phone screenshots (en-US listing) | exactly 2:1, RGB, no alpha |
> | `assets/screenshots/fr/01–08*.png` | Phone screenshots (fr-FR listing) | exactly 2:1, RGB, no alpha |
> | `assets/video/demo-child.mp4`, `demo-parent.mp4` | DPC/audience demo | see [`video-script.md`](video-script.md) |
>
> Screenshots were captured fresh on the emulator rig from the current build —
> night-blue default accent, icon, wizard, celestial child UI — as **two full
> language sets**: every screen in English for the en-US listing and in French
> for the fr-FR one (the app pushes the parent's language to the child, so both
> sets are genuine end-to-end captures, bedtime included). The rest of this
> file keeps the specs and the reasoning.


Current, official pixel/format rules, and an exact mapping from the real
screenshots already in `docs/images/` to Play's store-listing slots. Rules
below are from Play Console Help's graphic-asset documentation (see
Sources); Play Console's own uploader is authoritative if it ever disagrees
with a number here — always confirm at upload time.

## What Play requires

| Asset | Required? | Format | Size |
|---|---|---|---|
| App icon | Yes | 32-bit PNG, **with** alpha channel | 512 × 512 px, ≤ 1024 KB |
| Feature graphic | Yes | JPEG or 24-bit PNG, **no** alpha channel | 1024 × 500 px |
| Phone screenshots | Yes, min 2 | JPEG or 24-bit PNG, no alpha channel | short side 320–3840 px, long side ≤ 2× short side (max 2:1 aspect ratio); up to 8 |
| 7" tablet screenshots | Only if a tablet listing is wanted | same format rules | same range; Play generates a tablet listing from phone screenshots if none are supplied, so these are optional for this app |
| 10" tablet screenshots | Same as above | same format rules | same range; optional, same reasoning |
| Promo video | Optional | Public/unlisted YouTube URL | n/a |

Sources: [Play Console Help — graphic assets, screenshots, video](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en); [Play Console Help — best practices for your store listing](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en).

Môme DM is a phone-only app (no tablet-specific layout work has been done
per `docs/architecture.md`/`README.md`), so **tablet screenshots and a promo
video are skippable** for this release — Play will render the phone
screenshots on tablet listing surfaces by default.

## The real screenshots need reprocessing before upload — do this first

`docs/images/README.md` documents 18 real PNG screenshots captured on the
two-emulator BLE rig. They are the right content, but **none of them can be
uploaded to Play as-is**: their dimensions exceed Play's maximum 2:1
aspect ratio, and they carry an alpha channel Play's screenshot slot
rejects.

Measured directly (`PIL Image.open(...).size`):

| Group | Files | Current size | Ratio | Problem |
|---|---|---|---|---|
| Child screens | `child-*.png` (6 files) | 1080 × 2400 | 2.22 : 1 | exceeds 2:1 max; RGBA (has alpha) |
| Parent screens | `parent-*.png` (12 files) | 1280 × 2856 | 2.23 : 1 | exceeds 2:1 max; RGBA (has alpha) |

Both groups are emulator captures with the full display height (status bar
+ 3-button/gesture nav bar included), which is what pushes them just past
2:1. Fix before uploading:

1. **Flatten alpha.** Re-export or convert each PNG to RGB (no alpha) —
   e.g. `magick input.png -background white -alpha remove -alpha off
   output.png`, or re-save from an image editor with "no transparency".
2. **Crop to 2:1 or tighter.**
   - Child screens: crop 1080 × 2400 down to **1080 × 2160** (drop 240 px
     total — e.g. 120 px off the top status-bar area and 120 px off the
     bottom nav-bar area; both are chrome, not app content, so cropping
     them loses nothing meaningful).
   - Parent screens: crop 1280 × 2856 down to **1280 × 2560** (drop 296 px
     total, same top/bottom split).
   - Cropping status/nav bars is also the more polished choice for a store
     listing regardless of the ratio rule — bare status/nav chrome doesn't
     help sell the app.
3. Re-verify the result is ≤ 2:1 and has no alpha channel before uploading
   (`Image.open(f).size` and `.mode` — mode should read `RGB`, not `RGBA`).

The three `.svg` diagrams in `docs/images/` (`enrolment.svg`,
`connectivity.svg`, `enrolment-sequence.svg`) are hand-drawn explainers, not
screenshots, and SVG isn't an accepted Play asset format regardless — they
stay in the README and are not candidates for the store listing.

## Phone screenshot order and captions

Recommended set: **8 of the 18** (the practical maximum Play shows well;
uploading all 18 dilutes rather than helps), alternating parent/child so
the listing tells the "two phones, one system" story instead of reading as
two separate apps. Order matters — the first 2–3 are what a browsing user
sees before they scroll.

| # | File (in `assets/screenshots/{en,fr}/`) | Caption EN | Caption FR |
|---|---|---|---|
| 1 | `01-child-launcher.png` | The child’s home screen — only the apps you chose. | L’écran d’accueil de l’enfant — seulement les applis choisies. |
| 2 | `02-parent-children.png` | Your children at a glance — connected, child mode on. | Vos enfants en un coup d’œil — connecté, mode enfant actif. |
| 3 | `03-parent-device.png` | Everything for one child: status, apps, bedtime, content. | Tout pour un enfant : état, applis, coucher, contenu. |
| 4 | `04-child-bedtime.png` | Bedtime, kept by the phone itself — with the real moon. | Le coucher, tenu par le téléphone — avec la vraie lune. |
| 5 | `05-parent-apps-picker.png` | Choose exactly which apps exist on their phone. | Choisissez exactement les applis présentes sur son téléphone. |
| 6 | `06-child-customise.png` | A little of the phone belongs to the child — name and moon, stored only there. | Une part du téléphone appartient à l’enfant — prénom et lune, gardés chez lui. |
| 7 | `07-child-parent-menu.png` | The child’s phone can always explain its rules. | Le téléphone de l’enfant sait toujours expliquer ses règles. |
| 8 | `08-parent-provision.png` | Guided setup: reset, scan, done — no QR shown pre-generation. | Installation guidée : réinitialiser, scanner, c’est fait. |

Captions are for caption overlays or the listing’s marketing — Play’s
uploader itself takes no captions. The provision screenshot is the wizard’s
*form* stage, before any code exists, so there is nothing to redact. The
night-galaxy launcher was dropped from the sets: it can only be captured
after 19:00 real time, and a same-language, same-theme pair mattered more —
the "Make it yours" dialog in slot 6 carries the child-ownership story
(and the moon) instead.

Two more worth having in reserve if Play's 8-slot limit is raised or a
future update wants variety: `parent-app-config.png` (the auto-built
settings form — a distinctive, hard-to-fake feature) and
`parent-appearance.png` (bilingual/theme story).

**Before using `parent-provision.png` on the store listing:** confirm the QR
code and Wi-Fi credentials are still blurred in the cropped version — the
existing file is deliberately redacted per `docs/images/README.md`, and
cropping must not accidentally sharpen or crop into the redaction in a way
that defeats it. If in doubt, re-capture rather than reuse.

## App icon — needs to be produced, but the hard part is already done

No 512×512 PNG exists in the repo yet; only launcher-density rasters up to
192×192 (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp`). This is not a
quality problem, though: the actual icon art
(`app/src/main/res/drawable/ic_launcher_foreground.xml` and
`ic_launcher_background.xml`) is a **vector drawable** (`108dp` viewport),
so it can be rendered directly at 512×512 with no upscaling artifacts —
e.g. open the adaptive icon in Android Studio's Image Asset tool, or render
the two vector layers to a 512×512 canvas with any SVG/vector renderer, and
flatten background+foreground (Play's icon slot does not do adaptive-icon
masking — it wants one flat square image, ideally with the visual weight
kept inside a centered ~66% "safe zone" the way Android's adaptive-icon
foreground already is).

## Feature graphic — does not exist yet, must be created from scratch

Nothing in the repo is close to 1024×500 landscape. This needs new design
work: the app name/wordmark, one or two device silhouettes suggesting the
"two phones, one Bluetooth link" idea (the hand-drawn `connectivity.svg` in
`docs/images/` is a good visual reference for the concept, though it can't
be used directly), no transparency, JPEG or 24-bit PNG. Keep text minimal —
Play crops/scales the feature graphic across many surfaces (search results,
category pages, TV-like surfaces on some devices) where small text becomes
illegible.

## Screenshot localization

Play lets screenshots differ per listing language. Given the parent-side
screens in the existing captures are in English and the child-side screens
are in French (per `docs/images/README.md`: "Parent UI is English, child UI
is French — the app is fully bilingual and that contrast is intentional"),
either:

- ship the same mixed-language set for both the `en-US` and `fr-FR`
  listings (defensible, since it demonstrates the bilingual feature — but
  potentially confusing to a French-only browser seeing English parent
  screens), or
- capture a second, French-parent-UI set for the `fr-FR` listing specifically
  (more work, more correct). Given this is a first release, shipping the
  mixed set for both listings and revisiting after publishing is the
  pragmatic choice — nothing about it violates policy, it's a polish
  question, not a compliance one.

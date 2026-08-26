# Play publishing package review — docs/play/

*Reviewed 2026-08-26 against the current Play Console requirements and the app as it exists in this repo (`app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, root `README.md`). Reviewer stance: adversarial, from the perspective of a human Play policy reviewer for a device-owner/DPC parental-control app.*

## Verdict

This is an unusually strong publishing package — the policy research in `policy-forms.md` is current, correctly sourced, and makes the right calls on the three hardest questions (target audience = adults, Data safety = no collection, `isMonitoringTool` = `child_monitoring`, all verified). The listings are within limits, honestly worded, and the FR copy is genuinely idiomatic. Nothing here violates policy. What stands between this folder and a submission is: **two blocking gaps** (no hosted privacy-policy URL — and the URL the docs predict will likely 404 — and no release signing config, both already acknowledged as open checklist items), **a cluster of risky items** led by a real internal contradiction about the child audience and by emulator artifacts baked into five of the eight screenshots per language, and a **missing-items list** dominated by newer Play Console forms the docs never mention (EU DSA trader declaration, Health declaration, Advertising-ID question). Fix the two blockers, recapture the screenshots once with a renamed child device, align the audience story across the three documents, and this app has a defensible path through review — with the Play Protect DPC-allowlist test remaining the one existential risk the docs already flag correctly.

Severity counts: **2 blocking · 8 risky · 6 polish**, plus a 12-item missing-items checklist.

---

## Blocking

### B1. Privacy policy has no live URL, and the predicted URL is probably wrong
**Files:** `docs/play/privacy-policy.md` (the "Publishing this policy" section), `docs/play/README.md` step 4.

Play Console will not let App content complete without a working, publicly reachable privacy-policy URL. Three problems:

1. **Nothing is published yet** — this is a known open item, but it gates every declaration form, so it should be the first thing done, not a step-4 afterthought.
2. **The documented URL will likely 404.** The file predicts `https://nosari20.github.io/momedm/play/privacy-policy.html` from a `/docs`-sourced GitHub Pages deploy. GitHub Pages' Jekyll only converts a Markdown file to HTML **if it has YAML front matter**; a bare `.md` with no front matter is copied through as a raw markdown file (served at `.../play/privacy-policy.md` as text), and the `.html` path 404s. The doc itself correctly notes some reviewers reject raw-text policy pages. **Fix:** add minimal front matter (`---`\n`title: Privacy Policy — Môme DM`\n`---`) to the top of the file (or commit a small standalone `.html`), enable Pages, then verify the final URL renders as HTML **in a logged-out/incognito browser** before pasting it into Play Console.
3. **The published page must be trimmed:** the "*Publishing this policy (for the developer)*" section and the placeholder effective date ("the date this app is first published") would otherwise appear on the public policy page. Set a real effective date at publish time and keep the developer instructions out of the rendered page (front-matter-excluded file, or move the instructions to `README.md`).

### B2. No release signing config — no uploadable artifact exists
**Files:** `app/build.gradle.kts` (verified: `release` build type has no `signingConfig`), `docs/play/release-steps.md` §1–2.

Already tracked in the checklist and the instructions in `release-steps.md` are correct and match the actual build file. Listed here because until it's done there is literally nothing to submit: no signed `.aab`, no closed-test track, no 14-day clock starting. Do it early — the closed-test calendar time (§ R6) is the long pole for this account, and every day the track isn't live is a day of that clock not running.

---

## Risky

### R1. "Friendly for ages 6–14" in the listings vs. the Adults-18+ target-audience declaration
**Files:** `docs/play/store-listing-en.md` ("Friendly for ages 6–14 — no rewards, no streaks…"), `store-listing-fr.md` ("De 6 à 14 ans — sans récompenses…"), vs. `policy-forms.md` §2 (target audience = Adults only).

The 18+ recommendation in `policy-forms.md` §2 is the **right call** for a parental-control app whose Play customer is the parent (this matches how the category actually declares). But Play's Target audience & content enforcement specifically flags listings **whose own copy states the app is designed for children** while the declared audience excludes them — and an explicit numeric child age range is the clearest possible trigger for a "your store listing indicates your app targets children" mismatch rejection. Saying the app manages *your child's phone* is fine and unavoidable for the category; claiming the product is *for* ages 6–14 is the part that collides with the declaration.

**Fix:** delete the explicit range from both listings — e.g. EN: "A calm launcher with a big clock … built for young kids, with no rewards, no streaks, no pressure mechanics" → drop "Friendly for ages 6–14 —" and keep the rest; FR: drop "De 6 à 14 ans —" likewise. Also be ready for the questionnaire's follow-up ("could your app unintentionally appeal to children?"): with child-styled star-field screenshots, answer it deliberately, not reflexively — the defensible line is that the child-facing screens shown are what the *managed device* displays, while the store product is a parent's utility.

### R2. `video-script.md` contradicts the target-audience position — dangerous language if reused in a form response
**File:** `docs/play/video-script.md`, opening paragraph ("it **targets children** as part of its audience… the child-audience declaration") and the "How to use them" section ("if asked during Families review, the target-audience follow-ups").

`policy-forms.md` §2 says: no child age brackets, no Families enrolment — so there *is* no "child-audience declaration" and no "Families review" in the plan. If sentences from this file were pasted into a reviewer response, they would directly undermine the 18+ declaration and could talk the reviewer into forcing Families enrolment. **Fix:** reword the framing to match §2 — the videos evidence (a) legitimate device-owner/DPC use and (b) the anti-stalkerware posture (visible, disclosed, child-readable); the audience is parents. All three documents (`policy-forms.md`, `store-listing-*.md`, `video-script.md`) must tell one story, because a reviewer dispute will be answered under time pressure by copy-pasting from them.

### R3. Emulator identity baked into 5 of 8 screenshots per language
**Files:** `assets/screenshots/{en,fr}/01-parent-children.png`, `03-parent-device.png`, `05-parent-apps-picker.png`, `07-child-parent-menu.png` (verified by viewing).

Visible in the shipped captures: the child device is named **"Google sdk_gphone64_x86_64"** (hero screenshot 01, page title of 03, "Model" row of 07), and a personal dev test app **"Connectivity Check — com.nosari20.connectivitytest"** appears in the app picker (05) and in the "Apps with settings" row (07). Not a policy violation, but it screams "developer rig, never used by a real family" to both users and the human reviewer of a *monitoring* app — exactly the audience you want to look finished for — and screenshot 01, the first thing a browsing parent sees, is ~85 % empty black around one card. Additionally the 2:1 crop clips the "Parent menu" title in 07 and cuts 03 mid-row at the bottom.

**Fix (one recapture session):** rename the child device to something human ("Max's phone" / "Téléphone de Max"), uninstall `com.nosari20.connectivitytest` from the rig image, ideally enrol a second child card so 01 isn't empty, and nudge scroll positions so no text is clipped by the crop. Everything else about the sets (specs, ordering, redaction) is right — see "What is already strong."

Note on the Google app icons (Calendar, Camera, Chrome, Clock, Gmail) visible in the child-launcher and picker shots: depicting real third-party app icons inside genuine screenshots of your own UI is normal practice across the category and is not an impersonation/metadata problem — no action needed there.

### R4. Data safety: two mandatory sub-answers the docs never cover
**File:** `docs/play/policy-forms.md` §3.

The "**No data collected**" recommendation is correct and I would submit it as written — Play's own definition exempts data that never reaches a developer or third-party server, the fallback answer is well-prepared, and the privacy policy tells the same story. Two gaps in the walkthrough, though, both mandatory parts of the same form:

- **Advertising ID:** the Data safety flow asks every app whether it uses advertising ID. Answer **No** — and verify the *merged* manifest of the release build contains no `com.google.android.gms.permission.AD_ID` (no GMS/ads dependencies exist in `app/build.gradle.kts`, so it should be clean, but a transitive dependency can inject it; check `app/build/intermediates/merged_manifests/`).
- **Account creation / deletion:** the form asks whether users can create an account. Answer **No** — which is also the complete answer to Play's account-deletion requirement (no deletion URL is required for apps with no accounts). Worth one explicit line in §3 so the person filling the form doesn't hunt for a "deletion URL" that isn't needed.

### R5. Play Protect DPC allowlist — still the existential unknown
**File:** `docs/play/policy-forms.md` §1 (analysis is good; nothing new found to contradict it).

Reiterated because everything else in this review is moot if QR provisioning is blocked on current retail devices. The docs' ordering is right: **test provisioning on a patched physical device before spending another hour on listing polish**, and if blocked, file the appeal immediately (weeks of latency, enterprise-framed criteria, family use case unproven). This is the only item in the package that can kill the product rather than the submission.

### R6. Closed-test requirement: numbers are right, but 2026 added a "genuine usage" bar
**File:** `docs/play/README.md` step 5.

The documented requirement — personal accounts created after 13 Nov 2023 need a closed test with **12 testers opted in continuously for 14 days** — is correct and current (the old figure of 20 was reduced to 12 in Dec 2024). What the doc doesn't yet reflect: as of 2026 Google also evaluates whether the opted-in testers **actually used the app**, not merely opted in. For an app whose full loop needs two phones and a factory reset, most testers will realistically only exercise the controller role — that's fine, but tell recruits explicitly to *open and poke the app repeatedly across the 14 days*, not install-and-forget, or the production-access application risks rejection for thin engagement. Line up the 12 people **before** starting the track; the clock is per-tester and continuous.

### R7. Icon is 24-bit RGB; the spec (and assets.md's own table) says 32-bit PNG
**File:** `docs/play/assets/icon-512.png` (verified `Format24bppRgb`), vs. `assets.md` "What Play requires" table ("32-bit PNG, **with** alpha").

512×512 and under 1 MB are fine; the console usually swallows 24-bit PNGs, but Play's published spec — quoted correctly in `assets.md` itself — asks for 32-bit. Thirty-second fix: re-export with an alpha channel (fully opaque is fine) so the uploaded file can't trip a validator, and so the folder's own spec table and its produced file agree.

### R8. The demo videos are necessary but not sufficient as DPC/monitoring evidence
**Files:** `assets/video/demo-child.mp4` (26 s, 1080×2400), `demo-parent.mp4` (33 s, 720×1280), `video-script.md`.

What they show (kiosk launcher → remote lock with Emergency call → unlock) is genuinely the core device-owner capability, well chosen. But a reviewer probing a device-admin parental-control app typically asks for exactly the things the clips *don't* show:

1. **Provisioning and consent** — how the app becomes device owner: the factory-reset QR flow, `GetProvisioningModeActivity` / `PolicyComplianceActivity` screens, i.e. the deliberate, disclosed grant. This is the single most likely follow-up request; record it once on the rig (the rig QR/PIN are throwaways per the script's own note — blur them anyway).
2. **The persistent notification** — the stalkerware-policy regime hinges on the always-visible foreground-service notification; one shade-pull in the child video would prove it in two seconds.
3. **The bedtime lock transition** — the marquee scheduled-lock feature, referenced by name in the listing.

Also: the forms want a **YouTube URL**, and neither file is uploaded yet — do the unlisted uploads before opening the App content forms, not when a reviewer asks. And the child clip is encoded at ~27 kbps (89 KB / 26 s, CRF 30); size is irrelevant on YouTube, so re-encode at CRF ~20 so text survives YouTube's own re-compression.

---

## Polish

### P1. The "bedtime" screenshots were captured at breakfast time
`assets/screenshots/en/04-child-bedtime.png` shows **"10:17 AM — Good night! Your phone wakes up in 20h 42min, at 7:00 AM"**; the FR twin shows 10:19 the same way. Charming screen, incoherent story — a bedtime lock at ten in the morning with a 20-hour countdown. Recapture with the emulator clock set to ~21:30 so the marquee feature reads true (`adb shell date` or extend the rig procedure).

### P2. Hero screenshot ordering
Even after the R3 recapture, consider leading with `02-child-launcher.png` (the most distinctive, instantly-legible screen) and demoting the sparse "My children" list — the first two slots carry most of the conversion weight.

### P3. Feature graphic: the struck-through "o" in "cloud"
Both `feature-graphic.png` and `feature-graphic-fr.png` render "No c~o~ud" / "Sans c~o~ud" with a slash through the *o* — presumably an intentional "crossed-out cloud" pun, but at store-search render sizes it reads as a font-rendering glitch. Either make the strike unmistakably deliberate (a small crossed-out cloud glyph) or use plain text. Otherwise both graphics are spec-clean (1024×500, 24-bit RGB, minimal text, good contrast) and the FR variant is proper parity.

### P4. Store-listing wording nit
EN "REQUIREMENTS" says "no internet, Google account or Play Services are needed for day-to-day use" while "WHAT'S NOT INCLUDED" says installing a new app "opens its Play Store listing to tap Install" (which does need Play on the child device). Both true, but a pedantic reviewer or user could read tension; a two-word qualifier ("for installs, the Play Store opens if available") resolves it. Optional.

### P5. App access form: attach the video proactively
The planned App access note (full functionality requires a paired second device over BLE) is exactly right; strengthen it by pasting the unlisted YouTube demo URL into that same notes field so the reviewer who can't pair devices can still *see* the app working. Costs nothing, pre-empts the most likely "we couldn't review the core functionality" bounce.

### P6. Release notes don't exist yet
No "what's new" text (EN + FR) is drafted anywhere in the folder; Play requires it per release, testers see it first. Two sentences each, keep with the listings.

---

## Missing items — things a real submission needs that exist nowhere in docs/play/

- [ ] **EU DSA trader declaration** — required for any app visible in the EU (mandatory since Feb 2025), and this is a France-based developer launching in FR first. As an individual distributing a free, non-monetized app, declare **non-trader** (the listing will show a "no trader status" consumer notice); if monetization ever appears, trader status with a published address becomes mandatory. Not mentioned in any file.
- [ ] **Health apps declaration** — Play Console's App content now asks every app; answer "not a health app." Absent from the step-4 form list in `README.md`.
- [ ] **Advertising ID declaration** (see R4) — add to the form walkthrough.
- [ ] **Account creation = No** as an explicit Data safety line (see R4) — closes out the account-deletion requirement with no URL needed.
- [ ] **Store settings:** final category commitment (the Tools-vs-Parenting reasoning exists in `README.md` step 3 but no decision is recorded), up to 5 store tags, and **contact details** — a public support email is mandatory (decide whether that's nosari20@gmail.com or an alias; note that an individual account also publicly displays the developer's legal name and country on every listing).
- [ ] **Countries/regions and pricing** — no file says where to distribute (start France + English-speaking markets? worldwide?) or records that the app is **Free — which is irreversible** on Play (a free app can never become paid).
- [ ] **Unlisted YouTube uploads** of both demo videos (planned in `video-script.md`, not done; gates R8/P5).
- [ ] **IARC contact email** — the content-rating questionnaire requires an email address and sends the rating certificate there; decide which one before starting the form.
- [ ] **Concrete 12-tester roster** — `README.md` step 5 discusses recruitment channels but no actual plan/list exists; this is the calendar-critical path (14 continuous days *after* recruitment).
- [ ] **Release notes EN/FR** (P6).
- [ ] **Post-launch vitals plan** — the app deliberately ships no crash-reporting SDK, so Play Console's Android vitals is the *only* crash/ANR telemetry; note somewhere that vitals must be checked routinely, because a crashing device-owner launcher is a bricked child phone and an ANR spike also degrades store ranking. Bad-behavior thresholds matter more than usual for a HOME-role app.
- [ ] **Developer-page assets** (optional): developer name, icon/header for the Play developer page — nothing prepared; low priority but decide deliberately.

*(Explicitly checked and NOT missing: tablet-screenshot decision — `assets.md` correctly skips them since Play falls back to phone shots; pre-launch-report expectations for the DPC — `release-steps.md` §7 covers exactly the right caveat that the crawler can only exercise the controller role; News/COVID/Government/Financial N/A answers — all listed in `README.md` step 4.)*

---

## What is already strong

- **The three hard policy calls are right, and verified.** `isMonitoringTool` is present in the shipped manifest with the correct documented value `child_monitoring` (checked against Play's own flag page); target audience Adults-18+ with no Families enrolment is the correct posture for the category (with the R1 copy fix); "No data collected" is the defensible Data safety answer for a genuinely serverless app, and the prepared fallback answer is exactly what a review dispute needs.
- **The `USE_EXACT_ALARM` → `SCHEDULE_EXACT_ALARM` fix was actually made in code**, not just recommended — the manifest matches the docs, and the reasoning is preserved. Same for the deliberate absence of `QUERY_ALL_PACKAGES` (targeted `<queries>` instead — avoids an entire declaration form) and the `neverForLocation` flags on `BLUETOOTH_SCAN`/`NEARBY_WIFI_DEVICES`, which keep the app out of the location-permission review lane.
- **Every character count in both listings is exact** (verified: EN 25/78/3960, FR 27/66/3989 — all within limits), the titles are clean of keyword stuffing, and the "WHAT THIS APP IS NOT" section is doing real anti-stalkerware policy work, as `policy-forms.md` §5 intends.
- **The French listing is a real translation**, idiomatic and consistent with the app's own `values-fr` vocabulary («Mode enfant», «vraie lune», «sans mécanique de pression») — clearly not machine output, and the FR asset set (screenshots + feature graphic) achieves full parity, which most indie listings never do.
- **All 16 screenshots and both feature graphics are spec-compliant as files** (verified: exactly 2:1, RGB, no alpha, within pixel bounds) — the reprocessing problem `assets.md` diagnosed was actually fixed, and the provisioning screenshot was correctly captured at the pre-QR wizard stage so there is nothing to redact.
- **`release-steps.md` matches the real build files** (no signingConfig today, versionCode 1, AAB-first flow, keystore-out-of-git pattern, sensible versionCode strategy for a two-role protocol app), and the closed-testing numbers (12 testers / 14 days, post-Nov-2023 personal accounts) are current — targetSdk 37 is comfortably ahead of the 2026 target-API bar.
- **The package anticipates its own rejections** — the ordered "common rejection paths" list in `README.md` step 7 and the "what to tell a reviewer" paragraphs are exactly the material that turns a review dispute from a panic into a paste.

---

### Sources consulted for currency checks
- [Play Console Help — Use of the isMonitoringTool flag](https://support.google.com/googleplay/android-developer/answer/12955211?hl=en) (value `child_monitoring` confirmed)
- [Play Console Help — App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) (12 testers / 14 continuous days; genuine-usage evaluation)
- [Play Console Help — Malware / stalkerware policy](https://support.google.com/googleplay/android-developer/answer/9888380?hl=en)

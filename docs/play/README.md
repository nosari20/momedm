# Publishing Môme DM on Google Play

This directory holds everything needed to prepare `edu.fnosari.momedm` for
Google Play, researched and written against the app as it exists in this
repo (`README.md`, `docs/architecture.md`, `app/src/main/AndroidManifest.xml`,
`app/build.gradle.kts`) and against current official Play Console policy
pages (cited throughout, with URLs, as of August 2026). Where a policy
question is genuinely unsettled, the relevant file says so plainly and
gives the safer option — don't take any single "recommended answer" here
as unconditionally final without re-reading the reasoning behind it.

## Files in this directory

| File | What it's for |
|---|---|
| [`policy-forms.md`](policy-forms.md) | **Read this one first, in full.** The recommended answer and reasoning for every Play form/declaration this app touches — target audience, data safety, restricted permissions, device-owner/DPC positioning, and the monitoring-vs-stalkerware line. Identifies two places where the *app itself*, not just a form answer, likely needs a change before submission. |
| [`store-listing-en.md`](store-listing-en.md) | App name, short description, full description — English, ready to paste, with exact character counts. |
| [`store-listing-fr.md`](store-listing-fr.md) | Same, in French — a real translation matching this app's existing bilingual vocabulary, not a machine gloss. |
| [`privacy-policy.md`](privacy-policy.md) | The full privacy policy text, plus how to publish it at a public URL via GitHub Pages and which exact URL to paste into Play Console. |
| [`assets.md`](assets.md) | Exact current Play graphic-asset specs, and a precise mapping from the real screenshots already in `docs/images/` to Play's store-listing slots — including a concrete, verified problem (existing screenshots exceed Play's max aspect ratio and carry an alpha channel) that must be fixed before upload. |
| [`release-steps.md`](release-steps.md) | The actual build mechanics for this repo: generating an upload keystore, wiring a signing config without committing secrets, the exact Gradle commands, versionCode/versionName strategy, and pre-launch checks. |

## Before you start: two risks that could block this app regardless of what's in these docs

Both are explained in full in `policy-forms.md` §1 — read that section
before investing time in store-listing polish, because if either of these
blocks the app, no amount of correct form-filling here fixes it:

1. **Google Play Protect's Device Policy Controller allowlist** (rolled out
   late 2025) can block the QR/device-owner provisioning flow itself —
   independent of Play Store publication — with an "App blocked to protect
   your device" message, because Môme DM is a custom DPC, not a registered
   Android Enterprise EMM. **Test provisioning on a current, patched device
   first.** If it's blocked, the appeal process's documented criteria are
   enterprise-framed, and it is not confirmed that a family/consumer DPC
   qualifies.
2. **`USE_EXACT_ALARM`** is a Play-restricted permission limited to
   alarm-clock/calendar apps. Môme DM uses it for the bedtime lock, which
   doesn't cleanly fit either category. `policy-forms.md` §4 recommends an
   actual code change (switch to a non-restricted, inexact-but-near-the-
   boundary scheduling approach, which the app's own documented
   "alarms never set state, they only trigger re-evaluation" design
   already supports) rather than relying on the declaration form alone.

## End-to-end publishing checklist, in order

Each step links to the relevant file in this directory where the detail
lives. Check off the app-level fixes first — they're cheaper to discover
now than after a submission is already in review.

### 0. Fix what the app itself needs before submitting

- [ ] Add the `isMonitoringTool` manifest meta-data (`policy-forms.md` §5) —
      required on every version code, every track, from the first upload.
- [ ] Resolve the `USE_EXACT_ALARM` question (`policy-forms.md` §4) — either
      change the scheduling approach, or budget time for the Permissions
      Declaration Form's extended review and accept the rejection risk.
- [ ] Test device-owner QR provisioning against Play Protect's DPC allowlist
      (`policy-forms.md` §1) on a current patched device. If blocked, start
      the appeal now — it can take weeks.
- [ ] Reprocess the screenshots (`assets.md`) — crop to ≤2:1 aspect ratio
      and remove the alpha channel; none of the existing PNGs in
      `docs/images/` can be uploaded as-is.
- [ ] Wire up a real release signing config (`release-steps.md` §2) —
      `app/build.gradle.kts`'s `release` build type has no `signingConfig`
      today.

### 1. Create the developer account

- Register at [play.google.com/console](https://play.google.com/console/)
  as an **individual/personal developer account** under Florent NOSARI.
  One-time **$25** registration fee.
- **Identity verification is required.** As of 2026, Google requires
  document (and sometimes selfie) identity verification for new developer
  accounts, taking a few business days; the account can prepare a listing
  and upload builds during this window but cannot publish until it clears.
  ([Play Console Help — Get started](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en))
- Note the name used for account verification must match the payment
  method used for the registration fee.

### 2. Prepare signing

- Follow `release-steps.md` in full: generate the upload keystore, wire the
  signing config via a gitignored `keystore.properties`, confirm
  `bundleRelease` produces a signed `.aab`.
- Enroll in **Play App Signing** at first upload (effectively mandatory for
  new apps) — Google holds the app signing key, the developer keeps the
  upload key. `release-steps.md` §1 explains the distinction.

### 3. Set up the store listing

- Paste `store-listing-en.md` and `store-listing-fr.md` into the Main store
  listing page for `en-US` and `fr-FR` respectively.
- Upload graphic assets per `assets.md`: 512×512 icon (render from the
  existing vector adaptive-icon layers — no new art needed), a new
  1024×500 feature graphic (does not exist yet, needs new design work),
  and the reprocessed phone screenshots in the order `assets.md`
  recommends, with the suggested captions.
- Set the app category (Tools or Parenting are the plausible fits — lean
  Tools given the "Adults only" target-audience answer in
  `policy-forms.md` §2, since Parenting-category browsing skews toward
  Families-policy-adjacent surfaces this app deliberately isn't opting
  into).

### 4. Complete each questionnaire and declaration form

Full reasoning for every answer is in `policy-forms.md` — this is just the
order to do them in inside Play Console's App content section:

- [ ] **Privacy policy URL** — publish `privacy-policy.md` via GitHub Pages
      (steps at the bottom of that file) and paste the resulting URL.
- [ ] **Ads** — declare no ads.
- [ ] **App access** — declare that all functionality is available without
      special access (there's no login-gated content); explain in the
      provided notes field that full functionality requires a second,
      paired device and BLE proximity, since a reviewer testing on a single
      device/emulator will otherwise see a controller with nothing
      connected.
- [ ] **Content rating questionnaire** — answer honestly per
      `policy-forms.md` §2; expect a low/general rating.
- [ ] **Target audience and content** — **Adults (18+) only**, no Families
      enrolment, no Restrict Minor Access. Full reasoning in
      `policy-forms.md` §2.
- [ ] **News apps** — not a news app, answer no.
- [ ] **COVID-19 contact tracing/status apps** — not applicable, answer no.
- [ ] **Data safety** — **No data collected**, with the fallback answer in
      `policy-forms.md` §3 ready if a reviewer disagrees.
- [ ] **Government apps** — not applicable.
- [ ] **Financial features** — not applicable (no payments, loans, crypto).
- [ ] **Permissions declaration** — should not be triggered for anything
      except possibly `USE_EXACT_ALARM`; see the checklist item above under
      step 0. Re-verify `ACCESS_LOCAL_NETWORK` and `PACKAGE_USAGE_STATS`
      against Play's current restricted-permission list at submission time
      (`policy-forms.md` §4 flags both as worth a final check, since one is
      a very new Android permission and the other feeds the monitoring-app
      policy question in §5).

### 5. Choose a testing track

Google Play requires **new personal developer accounts** (created after 13
November 2023 — true for this account) to run a **closed test with a
minimum of 12 testers, opted in continuously for at least 14 days**, before
production access can be requested. Testers who opt out and back in reset
their clock — the 14 days must be continuous per tester.
([Play Console Help — App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en))

Practical read for this app specifically: recruiting 12 genuine testers for
a niche, two-phones-and-a-factory-reset parental-control app is real work,
not a formality. Consider:

- Family, friends, or other parents willing to actually try the two-phone
  setup — the closed test should exercise the real enrolment flow, not just
  the controller UI on one device, since that's the flow most likely to
  surface both the Play Protect DPC-allowlist risk (§1 above) and ordinary
  bugs.
- Recruiting via a relevant online community (r/androiddev,
  r/ParentingAndTech-style forums, or this project's own GitHub) — be
  upfront that it's a real device-owner app requiring a factory reset on a
  spare/test phone, since that's a bigger ask than a typical closed-test
  install.
- Once the 12/14-day bar is met: **Dashboard → Apply for production** in
  Play Console, fill in the three-part application (closed-test summary,
  app/audience information, production readiness), and expect review to
  take roughly a week, occasionally longer.

### 6. Submit for review

- Create the production release (or promote the closed-test release) with
  the signed `.aab` from `release-steps.md`, the store listing from step 3,
  and every form from step 4 completed.
- Use Play Console's **Pre-launch report** on the testing track before
  promoting further — see `release-steps.md` §7 for what it can and can't
  meaningfully exercise for a device-owner app.

### 7. On rejection

- Read the rejection reason in Play Console's **Policy status** /
  **App content** area carefully — Play's rejection messages usually name
  the specific policy, which maps back to a section in `policy-forms.md`.
- Common, anticipatable rejection paths for this specific app, roughly in
  order of likelihood based on the research in `policy-forms.md`:
  1. `USE_EXACT_ALARM` declaration questioned or denied (§4).
  2. A monitoring/stalkerware-policy question, given the app's device-owner
     status and app-usage visibility (§5) — respond by pointing to the
     `isMonitoringTool` flag, the always-visible foreground-service
     notification, and the fact the app **is** the child's home screen.
  3. A Data safety mismatch question if a reviewer manually inspects the
     BLE traffic and reads "collection" more strictly than the reasoning in
     §3 — respond with the fallback answer already prepared there rather
     than arguing the point from scratch under review-response time
     pressure.
  4. A device-owner/DPC-use clarification request — respond with the
     "what to tell a reviewer" paragraph at the end of `policy-forms.md`
     §1.
- For anything not anticipated here: fix the actual issue if the app is at
  fault, update the relevant file in this directory to reflect the new
  answer (so it stays accurate for the next submission), and resubmit.
  Do not resubmit an unchanged app/listing hoping for a different automated
  reviewer — Play's appeal process expects either a genuine change or a
  substantive explanation of why the flagged behavior is compliant.

## What was not fully verifiable, gathered in one place

Everything below is called out inline in the relevant file too, but is
worth a second look before submission since policy in these specific areas
has been changing quickly (some of it within the last year as of this
writing):

- Whether Môme DM's provisioning shape (self-hosted APK, local Wi-Fi, no
  Android Enterprise EMM) is or isn't affected by the Play Protect DPC
  allowlist, and whether a family/consumer use case can succeed in that
  allowlist's appeal process (`policy-forms.md` §1).
- Whether `ACCESS_LOCAL_NETWORK` or `PACKAGE_USAGE_STATS` will be added to
  Play's restricted-permission declaration list (`policy-forms.md` §4) —
  neither was found there as of this research, but both are worth a final
  check at submission time given how new/sensitive they are.
- Whether completing Play Console's own developer identity verification
  will also satisfy the broader, separate Android Developer Verification
  scheme rolling out globally from 2027 (`policy-forms.md` §1) — relevant
  because this app's enrolment flow is, by design, a sideload.
- Whether Play's Data safety "collection" definition, written around
  app→server flows, gets read strictly enough by a human reviewer to
  require disclosure of this app's direct device-to-device BLE transfer
  even though there is no server (`policy-forms.md` §3).

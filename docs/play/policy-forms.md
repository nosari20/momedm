# Play Console forms and declarations — recommended answers and why

Written to be defensible to a human reviewer, not just to pass an automated
check. Every claim here is checked against the actual manifest
(`app/src/main/AndroidManifest.xml`), `README.md`, and `docs/architecture.md`
as they exist in this repo today. Where Google's own documentation doesn't
give a clean answer, that's stated explicitly, with the safer option
recommended.

**Read this whole file before touching Play Console** — two items below
(§1 and §6) are not just form-filling advice; they identify a real risk that
the *app itself* may need a change to pass, or a provisioning step that may
fail regardless of what this document says.

---

## 1. Device admin / device owner positioning — the biggest open risk

### What the app does

Môme DM's whole design depends on becoming Android **device owner** on the
child's phone (`DevicePolicyManager`, via `AdminReceiver` /
`GetProvisioningModeActivity` / `PolicyComplianceActivity` in the manifest),
provisioned by scanning a QR code during a factory-reset Setup Wizard flow —
**not** via a registered EMM, and not via Android Enterprise's
`afw#setup` enterprise-enrolment path. This is a **custom, independent
Device Policy Controller (DPC)**, self-built, not registered with Android
Enterprise, used for a consumer/family purpose rather than a business one.

### Two separate gates, easy to conflate

1. **Google Play Store review** (does the *listing* get accepted). Play's
   published Developer Program Policy does not categorically ban DPC-style
   apps — an app using `DevicePolicyManager` device-owner APIs for a
   legitimate, disclosed, non-deceptive purpose is not itself
   automatically a policy violation. What *is* clearly a violation is a
   device-admin app that hides itself, resists uninstall deceptively, or
   uses admin APIs to enable stalkerware-style monitoring (see §5). We
   could not find a Play Console Help page that names "device owner /
   device admin used outside an enterprise EMM context" as its own
   standalone rejection category — the closest documented mechanism is the
   one below, which is a **device-side** gate, not a listing-review gate.

2. **Google Play Protect's DPC allowlist** (does the *provisioning flow*
   even work on the device). This is the one to worry about. In late 2025,
   Google Play Protect began enforcing an **allowlist of Android Enterprise
   Device Policy Controllers**: during device-owner QR provisioning
   (including the same 6-tap-Setup-Wizard flow this app's
   `docs/architecture.md` documents), Play Protect checks whether the app
   being set as device owner is on Google's approved-DPC list, and if it
   isn't, provisioning is blocked outright with a message like **"App
   blocked to protect your device"** — independent of whether the app is
   published on Play, sideloaded, or (as this app does) served from a
   self-hosted APK over the parent's own hotspot. This is a **device-level
   Play Protect check tied to Google Play Services**, not a Play Store
   listing check, so publishing on Play does not by itself resolve it, and
   *not* publishing on Play does not avoid it either.

   Sources: [Android Enterprise Help — Approved DPC allowlist](https://support.google.com/work/android/answer/16694822?hl=en);
   community reports of the rollout and its effect on custom DPCs during
   QR provisioning ([Android Enterprise Community thread, "Play Protect
   blocking custom DPC app during 6-tap QR provisioning"](https://www.androidenterprise.community/android-enterprise-general-discussions-3/play-protect-blocking-custom-dpc-app-during-6-tap-qr-provisioning-appeal-submitted-2909);
   [Jason Bayton, "Google Play Protect is now the custom DPC gatekeeper"](https://bayton.org/blog/2025/12/the-dpc-allowlist/)).
   **This is recent (rolled out with essentially no advance notice per
   Bayton's account) and not yet covered by a clean, authoritative Google
   help article walking through exact scope — treat everything in this
   subsection as the best available reading of a moving target, not a
   settled fact, and re-verify directly against
   `support.google.com/work/android/answer/16694822` before relying on it.**

### Why this matters more than any store-listing wording

If Play Protect blocks the provisioning step, **the app's entire reason for
existing breaks on the child's phone**, regardless of what the Play Store
listing says, regardless of the Data safety form, regardless of the Target
Audience answer. This is not a "will the listing get approved" question,
it's a "will the enrolment screen in `README.md`'s own walkthrough actually
complete" question, and it is worth resolving *before* investing further in
store-listing polish.

### Recommended path, and why

1. **Test provisioning on a current, patched device before doing anything
   else.** If the QR/6-tap flow completes without a Play Protect block, the
   allowlist may not apply to this app's provisioning shape (self-hosted
   APK over local Wi-Fi rather than Play-hosted), or it may not yet be
   enforced on the test device's Play Services version. Confirm directly —
   don't infer from the general reports above.
2. **If blocked:** the appeal path described by both the Android Enterprise
   Help page and community reports requires demonstrating the DPC is used
   for a legitimate purpose and complies with Mobile Unwanted Software /
   Potentially Harmful App policy — but the appeal-review language found in
   research ("the review team must verify that the application is being
   used strictly for **enterprise** purposes") is explicitly enterprise-
   framed. **A family/parental-control use case may not fit the appeal
   criteria as documented**, which is a genuine, currently-unresolved risk
   for this app's core mechanism, not a wording problem. This could not be
   fully verified either way — Google has not published DPC-allowlist
   criteria that explicitly address consumer/family use cases.
3. **Safer option:** budget time to file the DPC-allowlist appeal early
   (it can take "a few days to several weeks" per community reports), in
   parallel with the rest of Play Store preparation, rather than
   discovering the block during a real family's enrolment after launch.
   Be exhaustively honest in the appeal about what the app is (a personal,
   open-source, non-commercial family tool, not an enterprise product) —
   given the appeal process is manually reviewed, an accurate description
   is more defensible than trying to frame a family app as an enterprise
   one.
4. Do **not** attempt to route around this by pointing the QR at
   Android Enterprise's `afw#setup` / enterprise-enrolment path instead of
   the plain device-owner QR flow this app already uses — that path expects
   an actual Android Enterprise-registered EMM backend, which this
   architecture (deliberately, per `docs/architecture.md`: "no cloud/Play
   EMM involved") does not have and should not try to fake.

### A related, broader change worth tracking: Android Developer Verification

Separately from the Play Protect DPC allowlist above, Google is rolling out
**Android Developer Verification** platform-wide: starting **30 September
2026** (Brazil, Indonesia, Singapore, Thailand first, global rollout from
2027), apps must come from an identity-verified developer to install on any
"certified" Android device (i.e. any device shipping Google Play/GMS) —
**regardless of install source**, explicitly including direct APK sideload.
([Android Developers Blog announcement](https://android-developers.googleblog.com/2026/06/android-developer-verification.html);
[Help Net Security — rollout timeline](https://www.helpnetsecurity.com/2026/06/19/android-developer-verification-rollout-markets/))

This matters here because Môme DM's own enrolment flow is, by design, a
**sideload** — the child's phone downloads the APK directly from the
parent's self-hosted HTTP server, not from Play. Given the developer is
France-based (per the bilingual EN/FR listing and `nosari20@gmail.com`),
the initial four-country rollout does not immediately apply, but the
stated global expansion in 2027 will. A power-user "advanced flow" opt-in
is reported to remain available for unverified-developer sideloads (with a
warning and a wait period), which would likely keep enrolment technically
possible even post-rollout, but with more friction for a parent than
today's flow has. Completing Play Console's own developer identity
verification (required for account registration regardless — see
`docs/play/README.md`'s checklist) may or may not automatically satisfy
this separate scheme; **this could not be fully verified** as of this
writing and is worth re-checking at
`support.google.com/android-developer-console/answer/16561738` closer to
any relevant rollout date.

### What to tell a reviewer if asked

If a Play reviewer does ask about device-owner usage (via the app's normal
review, not the DPC allowlist above): point to `docs/architecture.md`'s
protocol section and `README.md`'s "How it works" — the app is fully
open-source, the device-owner grant happens only after a factory reset the
parent performs deliberately, device-owner status is visible to the child
at all times (it *is* the home screen, with a permanent "Child mode" or
bedtime banner — see §5), and there is no remote code execution or
arbitrary command surface: the entire command set is the fixed, documented
list in `docs/architecture.md`'s "Commands" table.

---

## 2. Target audience and content questionnaire

### The question Play doesn't cleanly answer

Play's questionnaire asks for the app's **target audience age group(s)**
and treats that as who the app is *designed for and appropriate for* —
it does not have a clean category for "an app a parent installs and
configures, that a child then uses as their phone's operating environment."
([Play Console Help — Target audience and content](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en))

### Recommended answer: target audience = **Adults only (18+)**, do not
select any child age bracket, and do not enrol in Designed for Families.

**Reasoning:**

- The **installer, configurer, and Google Play account holder** for the
  role that matters for this checkbox is the parent. A parent downloads
  the app, sets it up as a controller, and *separately* provisions a second
  phone via factory reset and QR code — the child never independently
  discovers, searches for, or installs this app from the Play Store on
  their own account. The child-facing surface (`ManagedHomeActivity`, the
  home-screen launcher) is not something a child ever visits the Play
  Store to obtain.
- Selecting a children's age bracket pulls the app into the **Families
  policy requirements** wholesale — Families Self-Certified ads SDKs (this
  app has zero ads or ad SDKs, so moot either way), stricter content and
  data rules, and Designed for Families review — none of which fit an app
  whose actual Play Store *customer* is an adult parent.
- The safer, defensible framing: this is a **parent-facing utility app**,
  the same category Play already has plenty of precedent for (e.g. Google
  Family Link itself is not listed as a children's app on Play, despite a
  child using a phone it manages) — it is the parent's tool for managing a
  device, not a child's entertainment or educational app.
- Since the target audience is adults only, **do not** enable "Restrict
  Minor Access" either — there's no reason to block a curious teenager from
  viewing the listing, and doing so isn't required or particularly relevant
  to this app's actual use.

**Where this is genuinely uncertain:** Google's own guidance doesn't fully
resolve apps in this "parent installs, child is the primary hands-on user"
shape, and Family Link itself sits in a slightly different position (it's
a first-party Google product with its own dedicated policy relationship).
If a future Play review disagrees with "Adults only" and requests children
be added to the target audience, the safer fallback is to comply rather
than argue — re-read the Families Policy Requirements at that point (data
collection, ads, content) before responding, since adding children to the
target audience is not just a checkbox, it triggers a real compliance
obligation, and this app should already clear it easily (no ads, no data
collection to disclose) if it comes to that.

### Content rating questionnaire (IARC)

Complete Play's standard content-rating questionnaire honestly: no violence,
no sexual content, no gambling, no user-generated content, no real-money
transactions, no in-app purchases, no ads. The app itself displays no
content of its own beyond its UI chrome — it restricts *access* to other
apps and content, it does not present content. This should produce a
low/general rating on every regional rating body Play submits to.

### Ads declaration

**No ads.** The app has no advertising SDK anywhere in
`app/build.gradle.kts`'s dependency list, and no ad-serving code.

---

## 3. Data safety form

### The core question: does BLE device-to-device transfer count as "collection"?

Play's own definition: **"'Collect' means transmitting data from your app
off a user's device"** — specifically to the developer's server or a
third-party server.
([Play Console Help — Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en))
Data that is "only processed locally on the user's device and not sent off
device" is explicitly exempted from disclosure under that same page.

Môme DM's BLE traffic (app list, allowed apps, status, PIN hash, prefs,
schedule — the full set is documented in `docs/play/privacy-policy.md`)
does leave the originating device physically (it goes out over a Bluetooth
radio), but it goes to **another device the same family/user controls**,
never to a server the developer or any third party operates — there is no
server, full stop. Play's collection definition is written around
app→server and app→third-party-SDK data flows; a direct, unmediated,
user-to-their-own-second-device transfer with no intermediary is not the
scenario the form's "collect" language appears to target, and does not fit
"transmitted... to your server or a third-party server."

### Recommended answer: **"No data collected."**

Every one of Play's 14 data categories should be answered "not collected."
This is defensible because:

- There is no server, anywhere, operated by the developer or any third
  party. `app/build.gradle.kts` has no networking/analytics/crash-reporting
  SDK.
- `INTERNET` permission exists only to run the parent's own local HTTP
  server (`nanohttpd` dependency) that serves the APK to the child's phone
  during setup, and to open `market://` / browser links to the Play Store —
  neither sends user data anywhere.
- All family-configuration data travels directly between the two phones a
  single family owns, authenticated end-to-end by the app-layer HMAC
  handshake described in `docs/architecture.md` (mutual challenge-response,
  per-message MAC, no BLE-layer pairing relied upon as the trust boundary)
  — arguably satisfying even the *stricter* reading some developers apply,
  the Data safety form's separate **end-to-end encryption exemption** for
  data that leaves a device but "is unreadable by [the developer] or
  anyone other than the sender and recipient."

### Where this is genuinely uncertain, and the safer option

Google has not published explicit guidance for the "two devices, no server,
direct radio link" case, so this reading — while well-supported by the
definitions Google *has* published — has not been tested against a real
Play review for this exact app. **If a reviewer pushes back and insists on
disclosure** (treating the BLE transfer as "collection" and "sharing" even
absent a server), the safer fallback is:

- Declare the data types actually exchanged (app list/usage, device
  identifiers as `deviceId`, "Other" for schedule/prefs) as **collected**
  but:
  - **not shared** with any third party (true regardless of interpretation
    — the only "third party" a strict reading could invoke is the other
    family member's phone, which Play's sharing definition is about
    external companies/organizations, not the same household),
  - **not used for advertising or a purpose beyond the app's own
    function**,
  - **encrypted in transit** (true — see the HMAC/session-key mechanism),
  - **no user-initiated deletion request URL needed**, since there is no
    account (this only applies if Play's form requires it when "account
    creation" is offered; this app offers no such thing at all).
- Whichever way this is answered, **the answer must match this document and
  `docs/play/privacy-policy.md` exactly** — Play explicitly checks the Data
  safety form against the privacy policy and against what the app's binary
  actually does, and a mismatch is itself a policy problem independent of
  which reading of "collection" is correct.

### Evidence to keep on hand if asked

- `docs/architecture.md`'s protocol section (handshake, MAC, no cloud).
- The absence of any networking dependency beyond `nanohttpd` (a local HTTP
  server library) in `app/build.gradle.kts`.
- `android:allowBackup="false"` in the manifest — nothing is even in
  Android's own cloud backup.

---

## 4. Restricted permission declarations

Play requires a **Permissions Declaration Form** (App content → Permissions
declaration in Play Console) only for a specific, named set of
high-sensitivity permissions — not for every dangerous permission a manifest
declares.
([Play Console Help — Declare permissions for your app](https://support.google.com/googleplay/android-developer/answer/9214102?hl=en))
Checked against `AndroidManifest.xml`:

| Permission in manifest | Triggers Play's special declaration form? | Notes |
|---|---|---|
| `BLUETOOTH_SCAN` | No — flagged **`neverForLocation`** | Correctly avoids the location-permission declaration entirely. Keep this flag; removing it would pull this into the ACCESS_FINE_LOCATION-adjacent review category for no reason, since the app never needs location. |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | No | Standard runtime Bluetooth permissions, not in Play's restricted set. |
| `NEARBY_WIFI_DEVICES` | No — flagged **`neverForLocation`** | Same reasoning as `BLUETOOTH_SCAN`. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `INTERNET` | No | Normal-protection-level permissions, not restricted. |
| `ACCESS_LOCAL_NETWORK` | No (permission is new/uncommon enough that Play's declaration list may not yet name it explicitly) | Verify at submission time — this is an Android 16+ permission and Play's restricted-permission list could be updated to include it; re-check `answer/9214102` before final submission. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | No, but foreground service **types** have their own policy (apps must use the correct type for genuine ongoing use — `connectedDevice` fits this app's actual BLE-link-keepalive purpose correctly). | No action needed beyond what's already correct. |
| `POST_NOTIFICATIONS` | No | Standard notification permission. |
| `CAMERA` | No — CAMERA is a normal dangerous runtime permission, not on Play's restricted list (that list centers on SMS, Call Log, Accessibility Services, All Files Access, Query All Packages, and a few others). | Used only for QR re-pairing; no special form needed, but be ready to explain the use case in ordinary review if asked, since a device-owner app requesting camera is a combination worth a plain, honest one-line note in review comments if Play ever asks. |
| `RECEIVE_BOOT_COMPLETED` | No | Standard. |
| `PACKAGE_USAGE_STATS` | Not on Play's special restricted-permission declaration list as researched; it's a "special app access" the user (here: the app itself, as device owner) grants via system Settings, not a manifest-time restricted permission Play separately reviews. **Could not fully verify this has no separate Play policy angle — re-check `answer/9214102` and Play's "Sensitive app permissions" page directly before submission**, since usage-access-derived data (current foreground app) is exactly the kind of signal Play's monitoring-app policy (§5) cares about. | Cross-reference with §5 — this is the one permission in the manifest most likely to draw a monitoring-policy question, not a permissions-declaration-form question. |
| `USE_EXACT_ALARM` | **Yes — and this is a real risk, not a formality.** See below. |

### `USE_EXACT_ALARM` — the one permission likely to cause an actual rejection

Play's policy on the Exact Alarm permission restricts it to apps whose
**core, user-facing functionality** is precisely-timed — the named
acceptable categories are alarm-clock apps and calendar apps with timed
event notifications. Apps that declare it without qualifying are
disallowed from publishing.
([OMA support center summary of Google's Exact Alarm API policy](https://orangeoma.zendesk.com/hc/en-us/articles/9110068699548-Google-Play-policy-on-use-of-Exact-Alarm-API);
cross-reference against Play Console's Permissions Declaration Form process
in `answer/9214102`.)

Môme DM is not an alarm-clock or calendar app — it uses exact alarms
(`managed/LockAlarmReceiver` per the manifest) to wake the child's device
at the start/end of the bedtime window. This is a genuinely uncertain fit
against Play's named acceptable-use categories, and declaring
`USE_EXACT_ALARM` without a strong justification risks the app being
**disallowed from publishing entirely**, not just flagged.

**Recommended fix, and it's a code change, not a form-wording change:**
`docs/architecture.md`'s own design description says the lock state is
**never trusted from the alarm** — "Alarms only *wake* the device to
re-evaluate; they never set state... A missed, stale or duplicated alarm is
therefore harmless." That property means the app's correctness does not
actually depend on alarms firing at the exact second — a `WorkManager`
periodic check, or `AlarmManager.setWindow()` / `setExactAndAllowWhileIdle()`
scheduled a minute or two ahead of each boundary (both of which use the
**non-restricted** `SCHEDULE_EXACT_ALARM`-free or looser-timing APIs),
would satisfy the same "wake up near the boundary and re-evaluate" job
without declaring a restricted permission the app's actual use case may not
qualify for. **This is the one place in this whole review where the
recommendation is to change the app, not just the Play Console form** — see
`docs/play/README.md`'s checklist for where this fits before submission.

If the exact-alarm behavior is kept as-is, the safer path is to file the
Permissions Declaration Form honestly (App content → Permissions
declaration → Alarms & reminders), explain the bedtime-lock use case
plainly, include a short demo video showing the lock/unlock happening at
the scheduled boundary, and expect either a request for more justification
or a rejection — budget time for this before a submission deadline.

---

## 5. Monitoring vs. stalkerware — how this app stays on the right side

Play's Malware / Mobile Unwanted Software policy explicitly permits apps
"exclusively designed and marketed for parents to monitor their children,"
subject to a specific technical and disclosure regime, and explicitly
prohibits apps that "present themselves as a spying or secret surveillance
solution" or hide/cloak tracking.
([Play Console Help — Malware policy, stalkerware section](https://support.google.com/googleplay/android-developer/answer/9888380?hl=en);
[Play Console Help — Use of the isMonitoringTool flag](https://support.google.com/googleplay/android-developer/answer/12955211?hl=en))

### Required technical step: declare the `isMonitoringTool` manifest flag

This is **not yet in the manifest** and needs to be added:

```xml
<meta-data
    android:name="isMonitoringTool"
    android:value="child_monitoring" />
```

placed inside `<application>` in `app/src/main/AndroidManifest.xml`,
present on **every version code across every track**, per policy. This is
one of the few places in this whole review where the manifest itself needs
an addition, not just a Play Console form answer.

### Why Môme DM clears the acceptable-use bar, and how to say so

| Policy requirement | How this app meets it |
|---|---|
| Must not present as spying/secret surveillance | The app **is** the child's home screen. It cannot be hidden, backgrounded invisibly, or disguised as something else — `ManagedHomeActivity` is a `HOME`/`DEFAULT` launcher activity, visible every time the child unlocks the phone. |
| Must not be usable to track a spouse/other adult even with consent | The app's only managed role is a factory-reset **device-owner** setup — it cannot be installed as a hidden background monitor on an already-in-use adult's phone the way spyware is; provisioning requires wiping the target device, which is itself a strong practical barrier against covert spousal use. |
| Persistent notification while running | `ManagedLinkService` and `ControllerService` are both declared as foreground services (`foregroundServiceType="connectedDevice"`), which requires an ongoing, non-dismissable notification on both phones per Android's own foreground-service rules — this is enforced by the platform, not just app choice. |
| Unique icon identifying the app | The launcher icon (`ic_launcher`) is the app's real, single icon — no alternate/disguised icon exists anywhere in `app/src/main/res/mipmap-*`. |
| Disclose monitoring functionality in the store listing | `docs/play/store-listing-en.md` / `store-listing-fr.md` state plainly, in the "WHAT YOU CAN DO" section, that the parent can see what app the child is using and the device's status — this is not buried or euphemized. |
| Legality is the developer's responsibility per locale | Note this explicitly in mind if ever distributing beyond France/EU — this review does not attempt jurisdiction-by-jurisdiction legal analysis, which is out of scope for a Play-forms document. |

### The one place to be careful in wording, everywhere (store listing, this repo's own README, privacy policy)

Avoid words like "monitor without them knowing," "hidden," "secretly," or
"track" in isolation without qualification — even though nothing in this
app does that, word choice on the public listing is itself part of what a
reviewer checks. The store-listing copy in this directory already leans
the other way deliberately (an explicit "WHAT THIS APP IS NOT" section
stating the app is not covert) — keep that section in any future edits to
the listing copy, it is doing real policy work, not just honesty for its
own sake.

---

## 6. Summary checklist for this section

- [ ] Test QR/device-owner provisioning on a current patched device and
      confirm the Play Protect DPC allowlist (§1) does not block it, before
      relying on anything else in this document.
- [ ] If blocked, file the DPC allowlist appeal early — it can take weeks.
- [ ] Target audience: **Adults (18+) only**, no Families enrolment.
- [ ] Content rating: answer honestly — should land as low/general.
- [ ] Ads: **none**.
- [ ] Data safety: **No data collected**, with the reasoning in §3 ready to
      defend if questioned, and the fallback answer ready if not accepted.
- [ ] Permissions declaration form: **not required** for
      Bluetooth/Wi-Fi/Camera/foreground-service/usage-stats permissions as
      currently declared; **is required, and is a real rejection risk**,
      for `USE_EXACT_ALARM` — strongly consider the code change in §4
      before submission instead of relying on the declaration form alone.
- [ ] Add the `isMonitoringTool` manifest meta-data (§5) before the first
      submission, on every version code.
- [ ] Keep the store listing's explicit "not a covert tool" framing in any
      future edits.

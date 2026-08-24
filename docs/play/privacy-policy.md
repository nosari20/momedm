# Privacy Policy — Môme DM

*Effective date: the date this app is first published on Google Play. Update
this line whenever the policy text changes.*

Môme DM ("the app") is developed by Florent NOSARI. This policy covers the
Android application with package id `edu.fnosari.momedm`, distributed on
Google Play and at the source repository
[github.com/nosari20/momedm](https://github.com/nosari20/momedm).

## The short version

Môme DM does not have a server. It does not have an account system. It does
not collect, store, transmit, sell, or share any personal data with the
developer or with any third party — because there is no channel for it to
travel over except a direct Bluetooth Low Energy (BLE) connection between a
parent's phone and a child's phone that the parent set up themselves. Nothing
your family does inside the app is visible to the developer, to Google (beyond
the standard Play Store distribution and diagnostics every app goes through),
or to anyone else.

## What data the app handles, and where it stays

Môme DM runs in one of two roles on a phone, chosen automatically:

- **Controller** (the parent's phone): a small control panel.
- **Managed** (the child's phone): the device owner and home screen.

The two roles exchange the following over a direct, authenticated,
point-to-point BLE connection — never over the internet, and never through
any server the developer operates, because no such server exists:

- The list of apps installed on the child's phone, and which of them the
  parent has allowed.
- The child device's online/offline presence, battery level, and (only if the
  optional Usage Access permission was granted during setup) the package name
  of the app currently in the foreground.
- The night-lock schedule and current lock state.
- A **hash** of the parent PIN (PBKDF2-HMAC-SHA256, 20 000 iterations, salted)
  — the plaintext PIN is never transmitted or stored on the child's phone.
- Display preferences: language, theme, and accent colour.
- A one-time pairing secret, generated on the parent's phone during setup and
  shown as a QR code, used to authenticate the BLE link.

None of this data leaves the pair of devices it concerns. It is not sent to
the developer, to any analytics or advertising service, or to any cloud
storage — the app has no networking code that talks to anything except the
child's own Wi-Fi hotspot (for the one-time APK download at setup) and the
Google Play Store / Google account flows the user explicitly opens (see
below).

## Where data is stored

All of the data above is stored **locally on the two phones**, in the app's
private storage (Android's Jetpack DataStore), protected by the operating
system's normal app-sandboxing. There is no cloud backup: the app declares
`android:allowBackup="false"`, so none of its data is included in Android's
automatic device backups either.

## Permissions the app requests, and why

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | The BLE link between the two phones. `BLUETOOTH_SCAN` is requested with `neverForLocation`, so Android does not treat it as a location permission and no location data is derived from it. |
| `NEARBY_WIFI_DEVICES` | Setting up the parent phone's local hotspot for the one-time APK transfer. Also requested with `neverForLocation`. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Running the local hotspot / HTTP server that serves the APK to the child's phone at setup, on the parent's phone only. |
| `ACCESS_LOCAL_NETWORK` | Android 16+ requires this before the parent's phone can serve the APK to the child's phone over the local network during setup. |
| `INTERNET` | Needed for the local HTTP server that serves the APK to also be reachable, and to open Play Store listings for app installs. The app makes no other network requests. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` | Keeping the BLE link alive while the app is not in the foreground, with a visible, persistent notification on both phones so it is never hidden from either parent or child. |
| `CAMERA` | Scanning a QR code, only when re-pairing an already-provisioned child's phone to a (possibly new) parent phone. |
| `RECEIVE_BOOT_COMPLETED` | Restarting the link and re-evaluating the lock schedule after the child's phone reboots. |
| `PACKAGE_USAGE_STATS` | Optional. If the parent grants it during setup, lets the child's phone report which app is currently in the foreground, so the parent can see it. Skippable, and nothing breaks if it is skipped — the status is simply left blank. |
| `USE_EXACT_ALARM` | Waking the child's phone at exactly the right minute to start or end the bedtime lock. |

Every one of these permissions is used only to make the direct phone-to-phone
BLE connection work, or to let the child's phone act as device owner as
described below. None of them is used to collect data for the developer.

## Device owner / device administrator

On the child's phone, Môme DM is installed as the Android **device owner**
(via `DevicePolicyManager`, following a factory reset). This is what lets it
become the home screen, restrict which apps can be launched (Android's lock
task mode), and lock the phone completely on a schedule. Being device owner
gives the app broad control over the child's phone by design — that is the
feature — but it does not give the app, or the developer, remote access to
anything outside what is described in this policy. There is no remote
support channel, no remote screen access, and no server the developer can use
to reach into a family's devices.

## What the app does not do

- It does not use analytics, crash reporting, or advertising SDKs.
- It does not create a user account of any kind — there is nothing to sign
  into and nothing to delete, because nothing is stored anywhere except the
  two phones themselves.
- It does not sell or share data with third parties, because it does not
  collect any data centrally in the first place.
- It does not track the child outside the features described in the app and
  in [the README](https://github.com/nosari20/momedm#readme) — see in
  particular the "Content restrictions" and "Keeping an eye on the link
  itself" sections, which describe exactly what is visible to the parent and
  exactly what is not.
- It cannot see anything on the child's phone once the two phones are outside
  Bluetooth range of each other, and it does not queue anything to send later
  — each command is either delivered live over BLE or not delivered at all.

## Data deletion

Because nothing is stored off-device, there is nothing for the developer to
delete on request. To remove all data the app holds:

- **On the parent's phone:** uninstall the app, or use **Settings →
  Controller → Regenerate secret** followed by uninstalling.
- **On the child's phone:** a factory reset removes the app and all of its
  data (the app is device owner and is not itself removable by an ordinary
  uninstall). The child's phone can also be un-enrolled by a parent through
  standard Android device-owner removal flows where the OEM permits it.

## Open source

Môme DM's full source code is public under the Apache-2.0 licence at
[github.com/nosari20/momedm](https://github.com/nosari20/momedm). Anyone —
not just Google's reviewers — can read exactly what the app does with the
permissions and data described above; nothing here relies on trust alone.

## Children's privacy

Môme DM is installed and configured by a parent, on the parent's own phone
and on a phone the parent owns and factory-resets for the child. The app
collects no personal information from the child for the developer's own use;
the only data handled is the family's own configuration, exchanged directly
between the two phones the parent controls, as described above. See
`docs/play/policy-forms.md` in this repository for how this app answers
Google Play's Target Audience and Families questionnaires.

## Changes to this policy

If this policy changes, the updated version will be published at the same
URL (see "Where this policy is published" below) and the version history is
visible in the project's git log, since this file lives in the app's public
source repository.

## Contact

Florent NOSARI — nosari20@gmail.com — or open an issue at
[github.com/nosari20/momedm/issues](https://github.com/nosari20/momedm/issues).

---

## Publishing this policy (for the developer)

Google Play requires a **publicly reachable URL** to a privacy policy on the
Store Listing page of Play Console (App content → Privacy policy). A file
inside the git repo is not itself reachable by a browser, so publish it with
GitHub Pages:

1. In the GitHub repo, go to **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **Deploy from a branch**.
3. Branch: `main`, folder: `/docs` (this repository already keeps its docs
   under `docs/`, so no restructuring is needed) — or `/ (root)` if a plain
   Markdown render at the repo root is preferred instead; either works as
   long as the chosen file is reachable.
4. Save. GitHub Pages serves Markdown files as rendered HTML automatically
   when Jekyll is left on (the default), so no build step is required for a
   single Markdown file.
5. The resulting URL will be:

   ```
   https://nosari20.github.io/momedm/play/privacy-policy.html
   ```

   (GitHub Pages serves `docs/play/privacy-policy.md` at that path once Pages
   is enabled with the `/docs` source; confirm the exact rendered path in the
   repo's Pages settings after first deploy, since Jekyll's default
   permalink scheme can also serve it without the `.html` suffix as
   `.../play/privacy-policy/`.)

   Use whichever exact URL loads correctly in a private/incognito browser
   window — that is the one to paste into Play Console. Test it logged out of
   GitHub, since Play's reviewers will not be authenticated.

Alternative, if a cleaner URL is preferred: copy this file's contents into a
minimal static HTML page and publish it as a GitHub Pages project site, or
host it anywhere else that is free, doesn't require a login to view, and
that the developer controls (a personal domain, a Gist rendered via
raw.githubusercontent.com is **not** acceptable — Play wants an HTML page,
not a raw text file, and some reviewers reject bare `raw.githubusercontent.com`
links). GitHub Pages is the simplest option that stays inside this repo.

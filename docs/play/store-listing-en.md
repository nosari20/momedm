# Store listing copy — English (en-US)

Ready to paste into Play Console → **Grow → Store presence → Main store
listing**, language English (United States). Character counts below were
measured with a Python `len()` on the exact text (counts Unicode code
points, matching how Play Console counts), not `wc`, since the text contains
a curly apostrophe, an em dash and a bullet.

## App name

Limit: 30 characters.

```
Môme DM: Parental Control
```

**25 / 30 characters.**

The app's on-device label (`app_name` in `strings.xml`) is just "Môme DM" —
this listing title adds "Parental Control" only for search and clarity on
the store page; it is not a claim the app makes anywhere in its own UI.
Google Play generally tolerates a listing title that's a superset of the
launcher label; it must not be misleading, and this one isn't a stretch.

## Short description

Limit: 80 characters.

```
Parental control with no cloud, no account: just Bluetooth between two phones.
```

**78 / 80 characters.**

## Full description

Limit: 4000 characters.

```
Môme DM is a parental-control app for families, built around one idea: it should keep working even when your phone isn't nearby.

Most parental-control apps route your child's activity through a company's servers. Môme DM doesn't. It's one app, installed on two phones, that talk to each other directly over Bluetooth — nothing else. No cloud, no account, no subscription, no ads, and no tracking or analytics of any kind.

HOW IT WORKS

Install Môme DM on your own phone and on your child's phone. On your child's phone, it's set up during a factory reset as the device owner and becomes the home screen. On your phone, it's a control panel. From then on, the two phones talk only over Bluetooth Low Energy, in range of each other. Wi-Fi is used exactly once, to get the app onto your child's phone during setup.

Because the rules live on your child's phone, they keep working when your phone is out of range, asleep, or has a dead battery. Your phone is a remote control, not something the rules depend on.

WHAT YOU CAN DO

• Choose which apps exist. Pick the apps your child may use; everything else doesn't appear on their home screen and can't be launched. Optionally pin them to one single app.

• Set content restrictions. Three levels — off, moderate, strict — turn on SafeSearch, Chrome's Safe Browsing and YouTube's Restricted Mode, plus an optional family DNS filter that applies phone-wide rather than to one browser. The app is upfront about what each setting does and doesn't cover.

• Configure other apps. Many apps declare their own manageable settings (Chrome declares hundreds). Môme DM reads what an app declares and builds a settings form for it automatically — nothing is hardcoded.

• Set a bedtime. Pick a window for school nights and a separate one for weekends, and your child's phone locks itself completely — no apps, just a clock — and reopens on schedule, even with your phone nowhere nearby.

• Use a PIN for exceptions. Typing your PIN on your child's phone pauses restrictions for ten minutes, then they resume automatically. The PIN is hashed before it ever leaves your phone.

• See what's going on. Which apps are allowed, what your child is using right now (if you've granted usage access), battery level, and whether the phone is locked — plus a log of the Bluetooth connection itself, so pairing problems don't fail silently.

• Match your family's look. Theme, accent colour and language (English and French, fully bilingual) are set on your phone and pushed to your child's.

WHAT THIS APP IS NOT

It's a parenting aid, not a security product. A factory reset removes it, and a sufficiently determined teenager will eventually find the edges of what it restricts. It's built for the ordinary case — agreeing on rules as a family and having the phone hold them — not for defeating someone who is actively trying to bypass it. Everything the app does on your child's phone is visible there: it's the home screen, it shows a "Child mode" banner, and there is no hidden or disguised mode. This is a family tool, used openly, not a covert monitoring tool.

REQUIREMENTS

Two Android phones on Android 14 or newer. The child's phone must be factory reset to be enrolled, since Android only grants the permissions this app needs during initial device setup. Bluetooth is required on both phones; no internet, Google account or Play Services are needed for day-to-day use once set up.

WHAT'S NOT INCLUDED

There's no silent app install — installing something new opens the Play Store listing for your child (or you) to tap Install, the same as anyone else would. There's no location tracking. There's no message, call or browsing-history monitoring. There's no remote camera or microphone access. If a feature isn't listed above, the app doesn't do it.

Môme DM is open source under the Apache-2.0 licence. The full source, including exactly what data the two phones exchange, is at github.com/nosari20/momedm.
```

**3946 / 4000 characters.**

## Notes for whoever pastes this in

- Keep the bullet character `•` as-is; Play's listing editor accepts plain
  Unicode bullets and they render correctly on the store page.
- The "WHAT THIS APP IS NOT" section is deliberate, not boilerplate — see
  `docs/play/policy-forms.md` for why an explicit, visible admission of the
  app's limits is part of how this app avoids being read as a stalkerware
  or covert-monitoring product by a Play reviewer.
- If Play's character counter (which may count differently for surrogate
  pairs/emoji — this text has none, so it shouldn't matter here) disagrees
  with the counts above by a character or two, trust Play Console's own
  counter at submission time and trim from the last paragraph first.

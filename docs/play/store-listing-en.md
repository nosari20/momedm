# Store listing copy — English (en-US)

Ready to paste into Play Console → **Grow → Store presence → Main store
listing**, language English (United States). Character counts measured with
Python `len()` on the exact text (Unicode code points, matching Play
Console’s counter). Refreshed 2026-08-26 against the shipped app: the staged
setup wizard, the child-designed launcher (star field, real-moon bedtime),
and the trimmed permission set.

## App name

Limit: 30 characters.

```
Môme DM: Parental Control
```

**25 / 30 characters.**

The app’s on-device label (`app_name` in `strings.xml`) is just “Môme DM” —
this listing title adds “Parental Control” only for search and clarity on
the store page; it is not a claim the app makes anywhere in its own UI.

## Short description

Limit: 80 characters.

```
Parental control with no cloud, no account: just Bluetooth between two phones.
```

**78 / 80 characters.**

## Full description

Limit: 4000 characters.

```
Môme DM is a parental-control app for families, built around one idea: it should keep working even when your phone isn’t nearby.

Most parental-control apps route your child’s activity through a company’s servers. Môme DM doesn’t. It’s one app, installed on two phones, that talk to each other directly over Bluetooth — nothing else. No cloud, no account, no subscription, no ads, and no tracking or analytics of any kind.

HOW IT WORKS

Install Môme DM on your own phone and on your child’s phone. On your child’s phone, it’s set up during a factory reset as the device owner and becomes the home screen. On your phone, it’s a control panel, with a guided step-by-step setup. From then on, the two phones talk only over Bluetooth Low Energy, in range of each other. Wi-Fi is used exactly once, to get the app onto your child’s phone during setup.

Because the rules live on your child’s phone, they keep working when your phone is out of range, asleep, or has a dead battery. Your phone is a remote control, not something the rules depend on.

WHAT YOU CAN DO

• Choose which apps exist. Pick the apps your child may use; everything else doesn’t appear on their home screen and can’t be launched. Optionally pin them to one single app.

• Set content restrictions. Three levels — off, moderate, strict — turn on SafeSearch, Chrome’s Safe Browsing and YouTube’s Restricted Mode, plus an optional family DNS filter that applies phone-wide rather than to one browser. The app is upfront about what each setting does and doesn’t cover.

• Configure other apps. Many apps declare their own manageable settings (Chrome declares hundreds). Môme DM reads what an app declares and builds a settings form for it automatically.

• Set a bedtime. Pick a window for school nights and another for weekends, and your child’s phone locks itself completely — no apps, just the time, tonight’s real moon and how long until morning — then reopens on schedule, even with your phone nowhere nearby.

• A home screen made for a child. A calm launcher with a big clock, a greeting by the name they chose, and a sky that becomes a quiet star field after dark. Friendly for ages 6–14 — no rewards, no streaks, no pressure mechanics.

• Use a PIN for exceptions. Typing your PIN on your child’s phone pauses restrictions for ten minutes, then they resume automatically. The PIN is hashed before it ever leaves your phone.

• See what’s going on. Which apps are allowed, what your child is using right now (if you’ve granted usage access), battery level, and whether the phone is locked.

WHAT THIS APP IS NOT

It’s a parenting aid, not a security product. A factory reset removes it, and a sufficiently determined teenager will eventually find the edges of what it restricts. It’s built for the ordinary case — agreeing on rules as a family and having the phone hold them — not for defeating someone who is actively trying to bypass it. Everything the app does on your child’s phone is visible there: it’s the home screen, it shows a “Child mode” banner, and there is no hidden or disguised mode. This is a family tool, used openly, not a covert monitoring tool.

REQUIREMENTS

Two Android phones on Android 14 or newer. The child’s phone must be factory reset to be enrolled, since Android only grants the permissions this app needs during initial device setup. Bluetooth is required on both phones; no internet, Google account or Play Services are needed for day-to-day use once set up. Fully bilingual, English and French.

WHAT’S NOT INCLUDED

There’s no silent app install — installing something new opens its Play Store listing to tap Install. There’s no location tracking, no message, call or browsing-history monitoring, and no remote camera or microphone access. If a feature isn’t listed above, the app doesn’t do it.

Môme DM is open source under the Apache-2.0 licence. The full source, including exactly what data the two phones exchange, is at github.com/nosari20/momedm.
```

**3960 / 4000 characters.**

## Notes for whoever pastes this in

- Keep the bullet character `•` as-is; Play’s listing editor accepts it.
- The “WHAT THIS APP IS NOT” section is deliberate, not boilerplate — see
  `docs/play/policy-forms.md` for why a visible admission of the app’s
  limits is part of how it avoids being read as covert monitoring.
- If Play Console’s counter disagrees by a character or two, trust the
  console and trim from the last paragraph first.

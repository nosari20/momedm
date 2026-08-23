---
name: localized-strings
description: Use when adding or changing any user-facing text in this app — keeps values/ and values-fr/ key-for-key, applies the family vocabulary rule, and avoids the Android escaping traps.
---

# User-facing strings

Every string exists in **both** locale files, with the **same key**:

- `app/src/main/res/values/strings.xml` — English
- `app/src/main/res/values-fr/strings.xml` — French

`StringsParityTest` (a JVM test) fails the build if the key sets diverge, in
either direction. Adding to one file and not the other breaks CI, not just the
French build.

## Vocabulary — this is a product rule, not a style preference

The app is used by parents and children, not administrators. Never put these in
anything a user reads:

| Never write | Write instead |
|---|---|
| MDM, device owner, DPC | *(nothing — say what it does)* |
| kiosk | child mode / mode enfant |
| lock task, pinning | locked / verrouillé |
| provisioning | setup / configuration |
| managed device | your child's phone / le téléphone de votre enfant |

Log messages and code comments are exempt — those are for developers. The rule
is about `strings.xml`.

Child-facing copy on the bedtime and launcher screens is warmer still: *Bonne
nuit !*, *Good night!*, *Bonjour !* — it is a phone a child looks at every day.

## Where to put a new string

Both files are organised in matching commented blocks (child launcher, parent
device page, settings, …). Put the new key in the **same block, same relative
position** in both files. A future reader diffing the two should see them line
up.

## Escaping traps

- **Apostrophes must be escaped** in Android resources: `d\'école`,
  `jusqu\'à`. An unescaped `'` is a build error, and French copy is full of them.
- **French typography wants a non-breaking space before `!`, `?`, `:` and `;`**.
  Write it as `&#160;`: `<string name="bedtime_title">Bonne nuit&#160;!</string>`.
- `%` must be doubled as `%%` unless it is a format specifier.
- Multiple format arguments must be positional — `%1$s`, `%2$d` — in both
  languages, because word order differs between them.

## Format arguments

Keep the same argument count and types in both files. If English is
`"Unlocks at %1$s"`, French must also take exactly one string argument, even if
the sentence is shaped differently: `"Déverrouillage à %1$s"`.

## Plurals

Use `<plurals>` rather than concatenating a count into a sentence. French and
English disagree about when a quantity is plural (French treats 0 and 1 alike,
English does not), and only `<plurals>` gets that right.

## Content descriptions

Anything tappable that has no visible label needs a `contentDescription` from a
string resource — the connection dot on the child's launcher, the hidden
long-press target that opens the parent PIN dialog. Never hard-code that text in
Kotlin; it has been caught in review here before.

## Verifying

```bash
./gradlew :app:testDebugUnitTest --tests "*StringsParityTest*"
```

Then look at the screen in both languages if the change is visible. The child
device's language is pushed by the parent, so the French path is exercised by
real users constantly — it is not a secondary locale.

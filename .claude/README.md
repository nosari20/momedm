# `.claude/` — Claude Code configuration for this repo

This directory configures [Claude Code](https://claude.com/claude-code) when it
works in this project. It is committed on purpose: the skills here encode
things that are expensive to rediscover — the two-emulator BLE rig, the
end-to-end shape of adding a management command, and a handful of traps this
codebase has actually fallen into.

If you do not use Claude Code, you can ignore this directory entirely. Nothing
in the build depends on it. Contributors reading it as documentation will still
find it useful — the skills describe real workflows.

| Path | What it is |
|---|---|
| `CLAUDE.md` | The project brief: architecture, conventions, gotchas. The repo root holds a one-line `CLAUDE.md` that imports it, so Claude Code still picks it up automatically. |
| `skills/emulator-rig/` | Bring up two emulators and drive a full parent↔child session |
| `skills/adding-a-command/` | Add a management command end-to-end, in dependency order |
| `skills/localized-strings/` | Add user-facing copy without breaking FR/EN parity |
| `agents/momedm-reviewer.md` | Reviewer subagent that knows this project's invariants |
| `settings.json` | Shared, non-secret project settings |

## Conventions these encode

The rules a change here has to respect, in short:

- Lock state is **never persisted** — it is recomputed from (schedule, manual
  lock, pause deadline, now).
- `protocol/` is pure Kotlin, no Android imports.
- Every string exists in both `values/` and `values-fr/`, key for key.
- Parent- and child-facing copy uses family words, never MDM jargon.
- Nothing logs a PIN, a shared secret, or a BLE payload in clear.
- `DevicePolicyManager` and `AlarmManager` code has no JVM fake: it is only ever
  judged on the emulator rig.

See [`../CONTRIBUTING.md`](../CONTRIBUTING.md) for the human version.

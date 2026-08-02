# Contributing to MG4 Control

## Critical context

This application runs with system privilege on a vehicle head unit and writes settings
that change how the car behaves. A defect here is not a bad user experience; it is a car
that brakes differently than the driver expects, or a head unit that will not boot.

Two consequences shape every rule below:

1. **The vehicle must stay stable.** A third-party app must never be the reason the head
   unit degrades. Unhandled exceptions in a system-privileged service are availability
   bugs.
2. **A write is a physical act.** Treat every code path that reaches the vehicle as if a
   person were pulling a lever, because that is what it amounts to.

## Ground rules

- **Small changes.** One concern per pull request. A large refactor of the vehicle layer
  is a conversation before it is a diff.
- **Explain the constraint in the code.** Every override of a platform default in this
  repository carries a comment saying what would break without it. That comment is why
  the constraint survives the next refactor. Add yours.
- **Never widen the write surface silently.** A new action means a new catalogue entry and
  a test that says which firmware it applies to.
- **Fail closed.** If a precondition cannot be read, refuse. Unreadable is not false.
- **Do not touch adjacent code.** Match the existing style even where you would have
  written it differently.

## Setup and testing

```bash
mise install          # pins the JDK — AGP needs Java 17
./gradlew :app:test   # JVM unit tests
./gradlew :app:assembleDebug
```

Without mise, set `JAVA_HOME` to a Java 17 installation. A newer JDK fails in the
`jlink` step with an unhelpful message.

Before opening a pull request:

- `./gradlew :app:test` passes.
- `./gradlew :app:assembleDebug` passes.
- If you touched the vehicle layer, say in the description what you verified **on a car**
  and what you could not.

## User interface changes

The interface follows [DESIGN.md](DESIGN.md), which is shared by every MG4 app. In
particular:

- Colour, spacing, type sizes and component styles come from the shared tokens. Do not
  introduce a hex value or a `dp` literal in a layout — use `@color/mg4_*`,
  `@dimen/*` and the `Widget.MG4.*` / `Text.MG4.*` styles.
- The token files `values/colors.xml`, `values-night/colors.xml`, `values/dimens.xml` and
  `values/styles_mg4.xml` are **generated**. Edit `tools/tokens/` in the workspace root and
  run `node tools/sync-tokens.mjs`; a change made directly in an app is overwritten.
- App-specific colours and dimensions go in `colors_app.xml` and `dimens_app.xml`, with a
  comment saying why the shared token does not fit.
- Minimum touch target 72dp, minimum text size 16sp, contrast at least 7:1 in **both**
  themes. Verify a new colour, do not estimate it.

## Coding standards

- Kotlin, matching the surrounding file. No new lint suppressions without a reason in the
  diff.
- No network calls. This app is offline by design.
- No new permissions without discussion. Minimal rights is a security property here, not a
  preference.
- Anything reaching the vehicle goes through the existing gate. If your code path cannot
  use it, that is the thing to talk about before writing the rest.
- Log nothing that identifies a vehicle or a person.

## Submitting a pull request

Include:

- What changes, and which firmware generations you believe are affected.
- What you tested, on what — emulator, head unit, or neither.
- Whether the change can affect vehicle behaviour. If yes, say so in the first line.

## Questions

Open a discussion rather than a half-finished pull request. For anything that looks like a
vulnerability, follow [SECURITY.md](SECURITY.md) instead — not a public issue.

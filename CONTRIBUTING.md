# Contributing to EVProfile

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

The interface follows [DESIGN.md](DESIGN.md), which is shared by every EVSuite app. In
particular:

- Colour, spacing, type sizes and component styles come from the shared tokens. Do not
  introduce a hex value or a `dp` literal in a layout — use `@color/ev_*`,
  `@dimen/*` and the `Widget.EV.*` / `Text.EV.*` styles.
- The token files `values/colors.xml`, `values-night/colors.xml`, `values/dimens.xml` and
  `values/styles_ev.xml` are **generated**. Edit `tools/tokens/` in the workspace root and
  run `node tools/sync-tokens.mjs`; a change made directly in an app is overwritten.
- App-specific colours and dimensions go in `colors_app.xml` and `dimens_app.xml`, with a
  comment saying why the shared token does not fit.
- Minimum touch target 72dp, minimum text size 16sp, contrast at least 7:1 in **both**
  themes. Verify a new colour, do not estimate it.

## Coding standards

- Kotlin, matching the surrounding file. No new lint suppressions without a reason in the
  diff.
- No network calls in `main` or `stable`. The only network/OTA code belongs in
  `src/unstable`, behind the existing HTTPS host and APK-certificate checks.
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

## Ways to help

Not every useful contribution is code:

- **Bug reports** — firmware generation, app version, what you did, what happened.
- **Feature requests** — describe the problem before the solution.
- **Documentation** — README, this guide, translations.
- **Pull requests** — see below.
- **Testing pre-releases** — install an `unstable` build and report what broke.
- **Sponsorship** — see below.

## Contributing efficiently

Maintainer time and AI quota are the scarce resources here, ideas are not. If you have
access to Claude Opus or another capable coding model, a finished pull request is worth
much more than a feature request: someone still has to design, write, test and verify the
request, and that someone has a limited quota too.

A pull request that lands quickly usually carries:

- The problem, in one or two sentences.
- The proposed solution, and what you rejected.
- The implementation, scoped to one concern.
- Tests — `./gradlew :app:test` passes.
- Documentation updated: README, CHANGELOG, this guide where relevant.

Generate the change locally with whatever model you have, then read every line yourself
before opening the PR. You are the author, not the model. Unreviewed generated code on a
path that reaches the vehicle will be sent back.

## Sponsorship

Maintaining EVSuite costs development time, test hardware and AI usage. If it is useful in
your daily driving, consider sponsoring through
[GitHub Sponsors](https://github.com/sponsors/malys). Sponsorship covers those costs and
gets fixes and features out faster.

## Questions

Open a discussion rather than a half-finished pull request. For anything that looks like a
vulnerability, follow [SECURITY.md](SECURITY.md) instead — not a public issue.

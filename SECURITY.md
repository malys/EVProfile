# Security policy

EVProfile is the component that actually writes to the car. It holds no credentials; the
stable channel contacts no server, while unstable contacts GitHub only for OTA. It runs
with system privilege and owns the gate that every other
vehicle-writing app has to go through — so security reports are welcome and taken seriously.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability. Use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead.

Include what you were able to do, on which firmware generation, and whether the vehicle was
moving. A proof of concept helps; a working exploit is not required.

## What is in scope

- Anything that lets another application on the head unit **bind the EVProfile bridge**
  without holding the signature permission.
- Anything that gets a road-behaviour write past the **speed gate** — including a code path
  that reaches a vehicle property without consulting it.
- Anything that lets a caller reach a vehicle property **not** exposed by the action
  catalogue, or pass a raw property id through the IPC surface.
- Privilege escalation through the profile store: a crafted profile that makes EVProfile
  write something no user could have asked for from its own UI.
- Anything that causes EVProfile to destabilise the head unit — an unhandled exception in
  a system-privileged service is a vehicle availability problem, not just a crash.
- Any way for stable to gain network access, or for unstable OTA to accept an unapproved
  host, redirect downgrade, oversized APK, or certificate mismatch.

## What is not in scope

- Requiring physical access to an unlocked head unit with developer mode enabled.
- A write being refused because the car was moving. That is the safety gate working.
- Vulnerabilities in the OEM firmware itself. Those belong to SAIC.
- A setting not being supported on your firmware generation. That is a compatibility gap,
  not a vulnerability — open a normal issue.

## Design decisions you should know about

- **Fail closed.** When the vehicle speed cannot be read, a road-behaviour write is
  refused, not attempted. The one exception is an explicit park-state check: if the gear
  reads park, the write is allowed even though speed was unreadable. That exception is
  narrow and deliberate, and it is documented where it is implemented.
- **The IPC surface takes no raw property ids.** Callers name catalogue actions. Widening
  the catalogue does not widen the contract, and a compromised caller can only ask for
  something a user could already do from the UI.
- **`VEHICLE_POWER_OFF` is deliberately unreachable.** Cutting the vehicle stays a human
  gesture.
- **Stable is offline by construction.** It has neither updater classes nor network
  permissions and is updated manually. Unstable keeps OTA in a separate source set,
  validates every redirect, caps download size, verifies the APK against the running
  certificate before copying it to Downloads, and still requires a manual installation.
  Neither channel exposes remote vehicle control.

See also [DISCLAIMER.md](DISCLAIMER.md), which covers the safety envelope rather than the
threat model.

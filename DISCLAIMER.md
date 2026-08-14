# Disclaimer — no warranty, no liability

**Use this software entirely at your own risk.**

This project is provided **"as is"**, without warranty of any kind, express or implied,
including but not limited to the warranties of merchantability, fitness for a particular
purpose and non-infringement. In no event shall the authors or contributors be liable for
any claim, damages or other liability, whether in an action of contract, tort or otherwise,
arising from, out of or in connection with the software or its use.

## What that means concretely

- The app runs on a **vehicle**, and its entire purpose is to **change vehicle settings**.
  Installing it is your decision and your responsibility, including any effect on the head
  unit's stability, your warranty, your insurance, or your vehicle's roadworthiness.
- **Drive profiles change how the car behaves.** Drive mode, regeneration level, steering
  weight, AEB and lane-keeping are not cosmetic preferences. A profile you applied
  yesterday is still applied today.
- **Do not reconfigure profiles while driving.** Do it parked. Nothing in this app needs
  attention on the move.
- **The speed gate is not a substitute for judgement.** Road-behaviour writes are refused
  above standstill, and refused writes are reported rather than silently dropped. That
  prevents a change from landing mid-drive; it does not make an ill-considered change safe.
- **Compatibility is inferred, not certified.** Not every build has been tested on every
  car. Your vehicle is the authority on what it accepts.
- Reading and writing vehicle settings uses **undocumented runtime interfaces**. They can
  change or disappear with any firmware update.
- Release builds are **minified with R8**, and the IPC bridge is resolved by name at bind
  time. Verify a release build on the vehicle before relying on it.

## Not affiliated

This project is **not affiliated with, endorsed by, or supported by** SAIC Motor, MG Motor,
or Google. MG, MG4 and related names and logos are trademarks of their respective owners.
They are used solely to identify compatibility with certain vehicles; no official origin,
certification or approval is claimed.

## Contributors

Contributors provide their work on the same terms: no warranty, and no liability for how
anyone uses it.

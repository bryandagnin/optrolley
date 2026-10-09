# Tier 4 — Low-Level Design and Implementation Plan

**Date:** 2026-10-09

---

## Status: OUTLINE

Not a design yet. It fixes just enough shape to choose a toolchain and write
the first tests. Each section is to be filled in after tier 3 is frozen, and
not before — detail written now would leak into the oracles.

---

## 1. Stack (D6 — proposed)

**Kotlin, native Android, two Gradle modules:**

- `:core` — plain Kotlin/JVM, no Android dependency. The observation model,
  the presentation function, the router, the simulator.
- `:app` — Android. UI (Jetpack Compose), storage (SQLite via Room), export,
  and later location.

What drives the split:

- **Every tier 3 oracle in sections A–E is a statement about a pure
  function** of list, history and store selection (O19 says so outright). That
  function can live entirely in `:core` and be tested without a device.
- **Nothing Android is installed** — `java`, `gradle`, `adb`, `sdkmanager` and
  `kotlinc` are absent from `PATH`, and `JAVA_HOME` / `ANDROID_HOME` are unset
  [evidence: checked on this machine, 2026-10-09]. `:core` needs only a JDK,
  so test-first work on the part that carries the risk can start well before
  the SDK and an emulator are sorted out.

**Strongest counter-case: this should be a web app.** An installable PWA
needs no Android toolchain at all, runs offline, and the pure core would be
the same shape in TypeScript. It gives up background geofencing — which D4
already ranks as a nice-to-have — and it puts the observation history in
browser storage, which the browser may evict. It is rejected here because the
brief says "android app", because the history is the one irreplaceable
artefact (T5) and deserves storage the app controls, and because location is
wanted eventually. If store detection is dropped, this case gets stronger and
should be reopened.

A weaker counter-case: Rust and Go are already installed and used in sibling
projects, and Kotlin is a new toolchain here. But a Rust or Go core on Android
needs the NDK and a binding layer on top of everything Kotlin needs, so it
adds Android tooling rather than avoiding it.

Library and plugin versions are deliberately not pinned in this document.
They are pinned at step 1, from whatever is current then.

## 2. Toolchain — not yet installed

Two stages, so the first does not wait for the second.

**Stage A — enough for `:core`.**

- A JDK, at the version the then-current Android Gradle Plugin requires (so
  it does not need replacing in stage B).
- Gradle arrives through the wrapper committed at step 1; no separate install.

**Stage B — enough for `:app`.**

- Android SDK: command-line tools, platform-tools, one platform, build-tools.
  Android Studio installs all of this and is the simplest route; the
  command-line tools alone are sufficient.
- Something to run on: an emulator image, or a phone with USB debugging.
- `ANDROID_HOME` set; SDK path in `local.properties` (ignored by git).

Record every installed binary in `~/.tool-paths`, as for the other
toolchains on this machine.

Decide at stage B, not before: application id, display name, minimum Android
version (Q6).

## 3. Layout

```
core/    Kotlin/JVM — model, presentation, router, simulator; tests beside
app/     Android — UI, storage, export; instrumented tests beside
spec/    the tiers
```

## 4. Observations (to be designed)

An append-only sequence of events: entry added / edited / removed, ticked,
unticked, marked not-here (store or chain), store created, store selected,
trip ended. Each carries a timestamp and enough identity to replay.

Everything else — the list, the trips, what each store has learned — is a
fold over that sequence (O20, O21). The export of F9 is that sequence,
serialised (O22).

## 5. The router — chosen by oracle, not by argument

The router turns a store's trip orders into a placement for each item. Tier 3
constrains it tightly enough that the choice need not be reasoned out in
prose: build the candidates behind one interface, run them through the
oracles and the simulator, keep the simplest that passes.

Candidates to try:

- **Precedence graph.** Record which item was ticked before which; present a
  linear extension. Satisfies O13 and O14 by construction when history is
  consistent. Needs a rule for contradictory evidence — which is also where
  O17 is won or lost.
- **Positional score.** Average each item's relative position across trips,
  weighting recent trips more. Simple and noise-tolerant; may contradict a
  directly observed pair on short lists, which would fail O14 `[hypothesis]`.
- **Pairwise strength model.** Fit a strength per item from pairwise
  outcomes. Principled under noise, heavier, and with the same O14 risk
  `[hypothesis]`.

Per O19, recency is counted in trips, never in wall-clock time.

## 6. The simulator — the oracle for O18

A synthetic shop and a synthetic shopper, so that "it gets better" is a
measurement.

- **Shop:** a sequence of zones, each holding items.
- **Shopper:** draws a list, walks the zones in order, picks up within a zone
  in arbitrary order, ticks as they go.
- **Noise, each independently adjustable:** ticks batched together; ticks
  made late; a walk in reverse; a rearrangement partway through the history;
  an item absent from the shop.
- **Measure:** disorder as defined in O18, per trip number.

**It is built from tiers 1–3 only, by an agent that has not seen any router**,
and frozen before the first router is written. A simulator written alongside
the thing it judges will share its blind spots.

## 7. To be designed

- **Storage.** The event sequence in SQLite; derived state cached and always
  rebuildable.
- **UI.** One screen is the product: the list, the store selector, tick and
  not-here within thumb reach. Everything else is secondary.
- **Trip boundaries.** The idle timeout of F4, and what happens to a trip
  interrupted by the app being killed.
- **Store detection (F10).** Staged as in open questions H1.

## 8. Internal predicates (tier 4 — NOT frozen)

None yet. They arrive with the design: invariants of the stored model, bounds
on update cost, the cycle-breaking rule.

## 9. Build order

0. **Confirm tiers 1–2; freeze tier 3.**
1. Toolchain stage A. Gradle wrapper and `:core`, with one deliberately
   failing test to prove the loop runs.
2. Observation model and replay (O20, O21).
3. Presentation with a router that learns nothing: sections, conservation,
   stability, cold start (O1–O12).
4. Simulator; fix the O18 thresholds against the paper-order baseline.
5. Router candidates; select by O13–O19.
6. Toolchain stage B. `:app` shell.
7. Storage, then the list screen.
8. Export and import (O22).
9. **Field trial**: real trips in real shops, testing the tier 1 §5
   assumptions. Expect this to send changes back up the tiers.
10. Store detection.

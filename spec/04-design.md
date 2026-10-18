# Tier 4 — Low-Level Design and Implementation Plan

**Date:** 2026-10-09
**Last updated:** 2026-10-18

---

## Status: DESIGN — first build

Tier 3 is frozen, so the design can be filled in. Sections 4–7 describe the
first build; the router is still chosen by data (§5), not here.

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

## 2. Toolchain — in containers

The JDK and Android SDK live in a container image, not on the host. That
keeps the build reproducible for whoever runs it, agents included, and leaves
the host with only Docker and `adb`, both already installed [evidence:
Rancher Desktop's Docker 29.5.3 and platform-tools 36.0.2 `adb` in
`~/.tool-paths`, 2026-10-18].

Two stages, so the first does not wait for the second. Both are the one image
in `docker/Dockerfile`; stage A simply does not use the SDK.

**Stage A — enough for `:core`.**

- A JDK, at the version the then-current Android Gradle Plugin requires (so
  it does not need replacing in stage B).
- Gradle arrives through the wrapper committed at step 1; no separate install.

**Stage B — enough for `:app`.**

- Android SDK: command-line tools, platform-tools, one platform, build-tools,
  installed by `sdkmanager` in the image. `ANDROID_HOME` is set there, so
  `local.properties` is not needed.
- Something to run on — below.

The versions are build arguments, pinned at step 1 like everything else (§1).

`compose.yaml` at the root runs it:

- **`build`** — the source bind-mounted at `/work`. Gradle's cache is a named
  volume, because a bind mount of a Windows drive is too slow to hold it.
- **`emulator`** — opt-in (`--profile emulator`). A prebuilt emulator image,
  `budtmo/docker-android`, watchable in a browser through noVNC on port 6080
  and reachable from `build` as `emulator:5555` over the compose network.

**Something to run on**, in order of preference:

1. **The emulator container.** Preferred because it lets instrumented tests
   run unattended, with no phone in the loop. It needs `/dev/kvm`, which
   needs WSL2 to pass nested virtualisation through. **Not yet working on
   this machine:** the WSL2 kernel reports `Hyper-V: Nested features: 0x0`
   and has no `/dev/kvm`, although `nestedVirtualization` defaults to on and
   the kernel has KVM built as modules [evidence: checked 2026-10-18]. The
   cause is a Windows update, KB5127648 (installed here 2026-10-14), that
   breaks nested virtualisation on AMD CPUs; Microsoft has acknowledged it
   and is working on a fix [microsoft/WSL#41917]. Uninstalling a security
   update is not worth it for this; route 2 or 3 serves until the fix ships.
2. **A phone with wireless debugging**, attached by `adb connect` from
   `build`. Needed for the field trial (step 9) whatever else is chosen.
3. **The host's `adb`**, with the container as its client: start the server
   on the host with `adb -a start-server`, and run the container with
   `ADB_SERVER_SOCKET=tcp:host.docker.internal:5037`. This is the route for a
   USB-connected phone, or an emulator running on Windows itself. `-a` makes
   the server listen on every interface, so stop it with `adb kill-server`
   when done.

Android Studio stays out of the container — a GUI there costs more than it
saves. If an IDE is wanted, it goes on the host; the container remains the
build of record.

Record any binary installed on the host in `~/.tool-paths`, as for the other
toolchains on this machine.

Decide at stage B, not before: application id, display name, minimum Android
version (Q6).

## 3. Layout

```
core/          Kotlin/JVM — model, presentation, router, simulator; tests beside
app/           Android — UI, storage, export; instrumented tests beside
spec/          the tiers
docker/        the build image (§2)
compose.yaml   build and emulator services (§2)
```

## 4. Observations and the presentation

**The record** is `List<Observation>` (`core/…/Observation.kt`): added,
edited, removed, ticked, unticked, not-here and its undoing, store and chain
creation and renaming, selection, trip ended, router chosen. Each carries
wall-clock milliseconds, used only for tie groups and idleness; order is list
position.

**Everything else is a fold** (`replay`): the list, stores, chains, the trip
in progress, each store's ended trips as tie-grouped `TripOrder`s, known
names, and the router in force.

**The clock never reaches the presentation.** Before recording an action,
the app calls `settle(history, now)`, which appends a `TripEnded(IDLE)` if the
trip has idled out. So whether a trip is in progress is itself in the
history, and `present(history)` is a pure function (O19).

**Why the list holds still (O7–O9, O28–O30) mostly by construction.** The run
is computed from ended trips only, and a router orders *items*, not entries:
the presentation filters the router's order to what is on the list. So within
a trip nothing that feeds the order changes, an added entry drops into the
place its item already has, and a tick changes only a mark. The exceptions
are handled explicitly: a mid-trip "not here" moves its entry at once (the
user's own act); a purchase that withdraws "not here" and a change of router
both wait for the trip to end.

**The export is the history** (`Export`), as JSON. Importing it is replaying
it, so O22 is O21 by another route.

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

**All three ship in the first build (D12, F11).** `Routers` lists them; an
observation chooses one. `score` replays the history and, for each ended
trip, asks each router what it would have presented from the trips before —
the lab view, and the way real data chooses the winner. A trivial baseline,
`FirstSeen`, stays in the list as the bar to clear.

**How they fared** against the oracle tests and the reference suite, placed
pairs at trip 5 (floor 13.6%; 13.5% from a rearrangement; margin 5 points):

| Router | Trip 5 | From rearrangement | Oracles |
|--------|--------|--------------------|---------|
| Positional — **default** | 14.0% | 18.1% | all pass |
| Pairwise | 13.8% | 18.0% | all pass |
| Precedence | 14.1% | 20.6% | fails O18 after a rearrangement |
| First seen (baseline) | 33.1% | 47.5% | fails O14, O17, O18 |

Positional is the default as the simplest that passes: a mean relative
position, constrained so directly observed and recently repeated pairs hold
(O14, O17), with no tuned constant beyond K. All three candidates read a trip
that runs against what was learned as walked from the other door, and learn
from its mirror — H6's proposed answer, which the field trial must confirm.

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

## 7. The app

- **Storage.** The history as an append-only file of JSON lines in app
  storage, flushed to disk before an action is shown as done. Not SQLite:
  the record is a sequence that is only ever appended and replayed, and a
  file is the simplest thing that does that; the derived state is rebuilt in
  memory at start (a few thousand events replay in milliseconds).
- **UI.** One screen is the product: the list in its sections, the store
  selector always visible, tick and not-here within thumb reach, the
  Elsewhere count announced. Secondary screens: stores and chains, the router
  setting with its lab scores, export and import.
- **Trip boundaries.** Idle ends are settled before each action and on
  resume, so a trip interrupted by the app being killed ends when the user
  next acts after the timeout.
- **Export and import** through the system file picker, which needs no
  storage permission. Import only into an empty history.
- **No permissions at all** in the first build (O23).
- **Store detection (F10).** Staged as in open questions H1; not in the first
  build.

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

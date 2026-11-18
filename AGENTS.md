# AGENTS.md — optrolley-autoroute

Orientation for AI agents working in this repo. Read this before touching
anything.

## What this is

An Android shopping list that sorts itself into the order you walk the shop.
It learns that order from the order you tick things off — no aisles, no
categories, no setup — and gets it more right each trip. It is store-aware:
each shop has its own learned route, and some items can only be got at
particular shops or chains.

"Optrolley" is **opt**imal trolley.

**The second build exists** and is in the owner's field trial. It ships
every router candidate side by side (D12) so real trips choose between them.

## Tiered spec-driven workflow

Work proceeds in tiers. **Do not skip ahead, and do not edit a lower-numbered
tier to make a higher one easier** — that inverts the whole point. If a tier
turns out to be wrong, say so explicitly and get agreement before changing it.

| Tier | File | State |
|------|------|-------|
| 1. Rationale, tenets, steering | `spec/01-rationale.md` | accepted; revised for the second build |
| 2. Functional spec | `spec/02-functional.md` | accepted; revised for the second build |
| 3. Predicates & oracles | `spec/03-oracles.md` | **frozen**, as restated for the second build |
| 4. Low-level design / plan | `spec/04-design.md` | design — second build |
| 5. Test plan | `spec/05-tests.md` | in force |
| 6. Implementation | `core/`, `app/` | second build |

**The second build** keeps the app's state and the record of trips, and
knows an item by an id that outlives its name; the action log is a journal
it writes and does not read (tier 4 §4, §7). Tier 4 §10 is the order it
was built in.

Open and deferred questions sit outside the tiers in
[`spec/open-questions.md`](./spec/open-questions.md). Answered ones graduate
into the tier 1 decisions table.

What the build gets wrong, or does unchecked, is in
[`spec/known-issues.md`](./spec/known-issues.md). When you find a defect you
are not fixing now, or ship something nobody has looked at on a phone, add
it there; take an entry out when it is fixed or checked.

**The field trial is under way** on the owner's phone, alongside the second
build. Its findings may send changes back up the tiers; tier 1 was drafted
from a one-line brief, so expect it to be corrected by real use, as it has
been once.

**Frozen means frozen.** The oracle tests (`core/src/test/.../oracles/`) and
the simulator (`.../sim/`) were written blind and judge the implementation
and the routers. Do not edit them to make code pass; an oracle that cannot be
met is raised with the owner (O18 was amended that way, with the reason
recorded in tier 3).

**Why 3 precedes 4.** Oracles must be design-independent: they state what is
observably true of the list as presented, the observation history and the
export, in terms a user could check without knowing how the app is built.
Writing the design first contaminates them into a restatement of that design,
at which point they ratify the implementation instead of constraining it.

**Why the router is not designed on paper.** Tier 3 pins its behaviour down
tightly enough that candidates can be built and ranked by the oracles and the
simulator (tier 4 §5–6). The simulator is written blind — from the spec, by an
agent that has not seen a router — and frozen before the first router exists.
Do not write the two together.

## Hard rules

The tenets from tier 1, inlined because they are not optional and you may not
have read that file. Ordered; the lower number wins a conflict.

1. **Nothing on the list is ever lost.** Routing reorders and sections. It
   never drops an entry or puts one out of reach. Nothing is moved to
   "elsewhere" by inference — only by something the user explicitly said.
2. **The list holds still while you shop.** No reordering during a trip.
   Ticking moves nothing else. Learning applies from the next trip.
3. **Never worse than paper.** With nothing learned it is a plain list in
   entry order. An item with no known position is shown as unplaced, not
   slotted in on a hunch.
4. **Learning is passive.** Never ask the user for an aisle, a category or a
   layout. Never make a correction mandatory.
5. **Trips are the record; the model is derived.** Ended trips are appended
   and never rewritten. Anything learned must be rebuildable from them
   alone. The list, items, stores and code links are current state, changed
   in place.
6. **Works with no connection.** No account, no server of its own. Nothing
   done in a shop needs the network; it is used only for the item library's
   lookups at home. The list and the trips are never sent anywhere.

## Things to know before designing

- **Unknown is not unavailable.** An item never bought at a shop is presented
  normally there. Absence of history says nothing.
- **Presentation is a pure function** of the list, the history and the
  selected store — not of the clock and not of chance. Recency is counted in
  trips. This is what makes the whole thing testable without a phone.
- **The premise is untested.** That people tick as they walk, and walk a shop
  the same way each time, are hypotheses (tier 1 §5). A field trial is in the
  build order to test them and may send changes back up the tiers.

## Layout

```
AGENTS.md                 this file
spec/                     the tiers, in order
spec/open-questions.md    live register, outside the tiers
spec/known-issues.md      defects and unchecked behaviour, outside the tiers
core/                     Kotlin/JVM: model, presentation, router, simulator
app/                      Android: UI, storage, export, scanning, look-ups
design/logo/              candidate logos, as SVG sources
docker/                   the build image: JDK + Android SDK
compose.yaml              build and emulator services
```


## Conventions

- **Toolchain: in containers.** No JDK, Gradle or Android SDK on the host;
  they live in the image in `docker/`, run through `compose.yaml`
  (`spec/04-design.md` §2). The host has only Docker and `adb`. Record any
  host binary in `~/.tool-paths`.
- **Run builds detached from input, into a log.** `docker compose run
  --rm build ./gradlew <tasks> > build.log 2>&1 < /dev/null; echo
  exit=$?`, then grep the log (`^e: `, ` FAILED$`, `BUILD`, `tests
  completed`). A build that can read stdin can wait on it forever with no
  output; one piped through `tail` shows nothing until it ends.
- **Building in parallel** (several agents or worktrees at once): give each
  its own Gradle cache, `GRADLE_CACHE_VOLUME=optrolley-gradle-<name> docker
  compose run --rm build …`, or take turns. One shared cache makes
  concurrent builds fail with "Timeout waiting to lock journal cache".
  Remove the extra volumes afterwards.
- **The phone** is driven over the host's `adb` (path in `~/.tool-paths`).
  Every interactive element has a Compose testTag exposed as a uiautomator
  resource id (`app/…/ui/Tags.kt`). Test data left on the phone pollutes the
  owner's real history: `adb shell pm clear dev.optrolley.autoroute` after.
- **Proposed stack:** Kotlin; `core/` has no Android dependency. See
  `spec/04-design.md` §1 for the reasoning and the case against it.
- Spec documents carry `**Date:** YYYY-MM-DD`; significant revisions add
  `**Last updated:**` without removing the original date.
- **Commit messages and comments state the semantic *why*, succinctly.** Not
  what changed — the diff already shows that.
- **History lives in git.** No changelog sections, no "added on <date>"
  annotations, no narrating an earlier state of a document in its own prose.
- **The builder's contact is not in the repository.** The catalogue asks
  each client to name a contact (tier 4 §7.3). It comes from
  `optrolley.contact` in `local.properties`, which is ignored, or from
  `OPTROLLEY_CONTACT`; a fork sets its own, and a build with none sends
  none.
- **Exported lists and histories are personal data.** They stay out of the
  repository; `/exports/` is ignored.

## Verification

No completion claims without fresh evidence. Before stating that tests pass
or the app builds, run the command in that same message and quote the output.
"Should work" is not evidence. `:core:test` has four expected failures, the routing methods that
do not meet O14, O17 and O18 (first-seen; precedence on O18); any other is
a regression.

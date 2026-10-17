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

**Nothing is built yet.** This is a spec skeleton. There is no code, no build,
and no Android toolchain on the machine.

## Tiered spec-driven workflow

Work proceeds in tiers. **Do not skip ahead, and do not edit a lower-numbered
tier to make a higher one easier** — that inverts the whole point. If a tier
turns out to be wrong, say so explicitly and get agreement before changing it.

| Tier | File | State |
|------|------|-------|
| 1. Rationale, tenets, steering | `spec/01-rationale.md` | **draft — awaiting the owner's confirmation** |
| 2. Functional spec | `spec/02-functional.md` | draft |
| 3. Predicates & oracles | `spec/03-oracles.md` | draft — **not frozen** |
| 4. Low-level design / plan | `spec/04-design.md` | outline only |
| 5. Test plan | `spec/05-tests.md` | outline only |
| 6. Implementation | `core/`, `app/` | not started — no toolchain |

Open and deferred questions sit outside the tiers in
[`spec/open-questions.md`](./spec/open-questions.md). Answered ones graduate
into the tier 1 decisions table.

**The next step is not code.** It is confirming tiers 1–2 and freezing tier 3
(tier 4 §9, step 0). Tier 1 was drafted from a one-line brief; treat it as a
proposal until the owner has corrected it.

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
5. **Observations are the record; the model is derived.** User actions are
   appended and never rewritten. Anything learned must be rebuildable from
   that history alone.
6. **On the device, offline, private.** No account, no server, no network
   permission.

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
core/                     (planned) Kotlin/JVM: model, presentation, router, simulator
app/                      (planned) Android: UI, storage, export
docker/                   the build image: JDK + Android SDK
compose.yaml              build and emulator services
```

`core/` and `app/` do not exist yet. They are created with the Gradle project
at build step 1, from then-current versions, so that nothing unbuildable is
committed in the meantime.

## Conventions

- **Toolchain: in containers.** No JDK, Gradle or Android SDK on the host;
  they live in the image in `docker/`, run through `compose.yaml`
  (`spec/04-design.md` §2). The host has only Docker and `adb`. Record any
  host binary in `~/.tool-paths`.
- **Proposed stack:** Kotlin; `core/` has no Android dependency. See
  `spec/04-design.md` §1 for the reasoning and the case against it.
- Spec documents carry `**Date:** YYYY-MM-DD`; significant revisions add
  `**Last updated:**` without removing the original date.
- **Commit messages and comments state the semantic *why*, succinctly.** Not
  what changed — the diff already shows that.
- **History lives in git.** No changelog sections, no "added on <date>"
  annotations, no narrating an earlier state of a document in its own prose.
- **Exported lists and histories are personal data.** They stay out of the
  repository; `/exports/` is ignored.

## Verification

No completion claims without fresh evidence. Before stating that tests pass
or the app builds, run the command in that same message and quote the output.
"Should work" is not evidence. Until the toolchain exists, nothing here can
be claimed to build or pass at all.

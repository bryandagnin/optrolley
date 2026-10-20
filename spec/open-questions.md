# Open Questions

**Date:** 2026-10-09
**Last updated:** 2026-10-19

A live register, deliberately outside the spec tiers because it turns over as
answers arrive. Answered items graduate into the decisions table in
[tier 1](./01-rationale.md) §4; only what is still open or deferred stays here.

---

## Open

None. Q1–Q6 were answered at the build go-ahead and are decisions D2–D13
in tier 1; the values they set are in tier 3.

---

## Proposed expansions

Pencilled in by the owner as next features. None is designed: each needs
the changes to tiers 1–3 listed under it, made and agreed before any code.
Barcode scanning (was H7) was brought forward and is now D14–D15 and F12.

### H1. Geofencing to select the shop

**Status:** proposed expansion. Was deferred (D4), staged behind the manual
selector, which stays.

**Stage 1 — suggest on open.** One location reading when the app is opened;
offer the nearest known store. Needs only while-in-use location permission.
Store locations are learned from where the user was when they selected a
store by hand (F10) — never typed in.

**Stage 2 — true geofence.** The app knows it has arrived without being
opened. On current Android this needs background location permission, which
is a separate and much more intrusive grant `[from memory — verify against
current platform docs before designing]`, and the usual geofencing API comes
with Google Play services; the platform's own proximity alerts are the
alternative. Worth it only if stage 1 proves insufficient.

**The owner asked for auto-select, which goes further than F10's
"suggest".** What the frozen spec already says about it:

- **Never mid-trip** (O10, F10). Detection only acts when no trip is in
  progress; arriving at shop B with a trip open at shop A changes nothing.
- **Only ever the manual choice** (D4). An automatic selection is an ordinary
  `Selected` observation, so learning and replay are unaffected. Whether it
  also records that it was automatic (for the lab, and to undo it) is a
  design question.
- **Always visible and one tap to undo** (O40). A wrong auto-selection
  teaches nothing until the user ticks, since a trip starts at the first
  tick — but the first tick would then teach the wrong shop. Suggest-first
  may be the safer default, with auto-select as an opt-in.
- **Location becomes personal data in the history.** Learning a store's
  location means recording where the user stood when selecting it; that
  lands in the export (F9), which `/exports/` already keeps out of the repo.
- **O23 holds** (location is not a network permission), and every other
  oracle must hold with location denied — the app stays fully usable without
  it.

Tiers to change: tier 1 D4 (suggest vs select), tier 2 F10, tier 3 new
oracles (e.g. no automatic selection during a trip; denied location changes
nothing).

### H8. Separate writing the list from shopping it

**Status:** proposed by the owner (2026-10-19); not designed.

Making the list (at home: typing, scanning empties, editing) and walking
the shop (one-handed: glance, tick, "not here") are different jobs with
different screens in mind. One screen serves both today. A split might give
the shop a read-mostly view — bigger rows, nothing that can edit by
accident — and the home a write view with scanning and editing at hand.

Questions before designing:

- **What switches modes?** By hand, or implied: a store selected and a trip
  under way (F4) already says "shopping". An implied switch must still leave
  every entry reachable (O3) and must never move the list (T2).
- **What the shop view can still do.** Adding mid-trip is allowed (O8, O30)
  and probably has to stay one tap away; the forgotten-item case is common.
- **Where scanning goes.** Write side only, as F12 already limits it to
  adding.

Tiers to change: tier 2 (the modes, and F12's place in them), tier 3
probably none — the oracles are about the list as presented, not which
screen shows it — unless the shop view hides an action, which O3 would need
to allow.

---

## Deferred

### H2. Chain as a starting guess for layout

A new branch of a familiar chain could borrow an order from its siblings
instead of starting cold. Rests on same-chain stores being laid out alike
`[hypothesis]`. Would need O16 restated, and provisional placements would
have to be distinguishable from learned ones to stay honest under T3.

**Revisit when** the field trial has history from two stores of one chain to
compare.

### H3. Planning a trip across several stores

Which shops to visit and in what order, given what is only available where.
A different optimiser on different data (D7). The Elsewhere section already
shows what a given shop cannot supply, which is the raw material.

### H4. Shared list

A household list is the most common ask of any shopping app and conflicts
head-on with T6. Would need a sync story that does not involve a server the
owner has to run. Export and import (F9) is the stopgap.

### H5. Drag-to-reorder as a teaching signal

Letting the user correct the run by hand, recorded as an observation.
Permitted by T4 (welcome, never required). Deferred because it is a second
source of evidence to weigh against ticks, and the first should be understood
on its own.

### H6. Walking the shop backwards

**Status:** built into every router candidate — a trip that runs against what
was learned is read as its mirror (tier 4 §5) — but unconfirmed in the field.

Entering by another door reverses the route. One reversed trip would read as
a rearrangement and unlearn a good order. Possible answers: recognise a trip
that runs against the model and learn from its mirror; or offer to flip the
run. Needs field evidence that it happens often enough to matter.

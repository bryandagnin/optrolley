# Open Questions

**Date:** 2026-10-09

A live register, deliberately outside the spec tiers because it turns over as
answers arrive. Answered items graduate into the decisions table in
[tier 1](./01-rationale.md) §4; only what is still open or deferred stays here.

---

## Open

### Blocking the tier 3 freeze

**Q1. Is tier 1 right?** D2 (routing means in-shop order learned from ticks)
was inferred from a one-line brief. D5–D7 and the tenet ordering are
proposals. Everything below rests on them.

**Q2. Where do unplaced entries go?** Proposed: ahead of the run, so they are
seen before setting off. The alternative is after it, so the learned part
reads cleanly and the unknowns are a sweep at the end. Affects F5 only.

**Q3. How fast should a rearrangement be absorbed?** K in O17, and the margin
and trip count in O18. Small K adapts quickly and is jumpy when one trip is
walked oddly; large K is steady and slow. Proposed K ≤ 3. Must be fixed
before any router exists.

**Q4. One list or several?** Proposed: one. Several lists (weekly shop,
hardware shop) are a natural ask, but stores already partition the list by
where things can be got, which may cover it.

**Q5. When are two typed names the same item?** Learning is keyed on item
identity, so "milk", "Milk" and "milk " splitting into three items quietly
defeats it. Proposed: match ignoring case and surrounding whitespace, nothing
cleverer, with manual merge and rename. Is "milk" the same item as "trim
milk"? Proposed: no.

### Blocking build step 6

**Q6. Application id, display name, minimum Android version.** Decide when
the Android toolchain goes in, against what is current then.

---

## Deferred

### H1. Automatic store detection

**Status:** wanted (D4), staged behind the manual selector.

**Stage 1 — suggest on open.** One location reading when the app is opened;
offer the nearest known store. Needs only while-in-use location permission.
Store locations are learned from where the user was when they selected a
store by hand (F10).

**Stage 2 — true geofence.** The app knows it has arrived without being
opened. On current Android this needs background location permission, which
is a separate and much more intrusive grant `[from memory — verify against
current platform docs before designing]`. Worth it only if stage 1 proves
insufficient in the field trial.

Either stage only ever supplies the same selection the manual picker does,
and neither changes the store mid-trip (O10).

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

Entering by another door reverses the route. One reversed trip would read as
a rearrangement and unlearn a good order. Possible answers: recognise a trip
that runs against the model and learn from its mirror; or offer to flip the
run. Needs field evidence that it happens often enough to matter.

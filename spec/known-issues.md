# Known Issues

**Date:** 2026-11-17

Things the build gets wrong, or does that nobody has checked, written down
so they are not rediscovered. Outside the tiers, like the
[open questions](./open-questions.md): a question is something to decide,
an issue is something to fix or to look at. An entry goes when it is fixed
or checked; if fixing it needs a decision, it moves to the open questions.

---

## Wrong

### K1. Undo after a join brings back the old name as a new item

Remove an entry, rename its item onto another item's name (joining them),
then press Undo on the "Removed" message: the entry comes back under the
old name, which by then is a new item with nothing learned (O56), not the
joined one.

*Why:* the undo looks the item up by its id when pressed, and falls back to
the name it had. A joined-away id is no longer in `State.items`, and
`State` does not say what it was joined into.

*Fix:* expose the aliases on `State`, or a `Book.itemNow(id)`, and resolve
through it in `AppViewModel.remove`'s undo. Rare: three deliberate steps
inside one snackbar's lifetime.

### K2. The scanner's Undo and Change do nothing after their item is joined away

A scan logged above the add bar keeps the id its item had (`ScanEvent.item`).
If that item is then joined into another while the line is still showing,
Undo and Change on it find no such entry and silently do nothing.

*Fix:* the same resolving as K1, in `AppViewModel.undone` and `relink`.

### K3. The lab is slow the first time it is opened after each start

It scores every routing method on every trip, and what it works out is kept
in memory only (tier 4 §4.2). Measured on the owner's phone with the first
build's code: 2.2 s at 130 trips, 7.4 s at 260 `[evidence: debug benchmark,
2026-11-15]`. Opening it again in the same run is immediate.

*Fix:* store the scores with the state, or score in the background as each
trip ends.

## Not checked on a phone

### K4. The join question opens over another dialog

Renaming onto a name in use shows a second dialog on top of the edit or
rename dialog. The Robolectric tests pass; nobody has seen it on a device.

### K5. The library screen

Built and tested off the device only. Its layout with the owner's real
items and codes (42 items, 57 codes when it shipped) has not been looked at.

### K6. The scan square and its timings

The square viewfinder, whether its outline is where codes are actually read,
and the values chosen for it (0.7 of the side, two readings within 0.7 s,
1 s of rest after a scan; tier 4 §7.1) were set without a camera in the
loop. The owner has scanned a trip with them; whether they are right has
not been reported either way.

## Not judged by any oracle

These are behaviours the code has and the tests written from tier 3 do not
check, because tier 3 does not say what should happen. Each is a sentence
missing from O55 or O57 before it is a defect.

### K7. A trip that ticked both of two items later joined

The code counts the joined item at the later of its two ticks (tier 4
§4.1). One unit test of the implementer's covers it; the blind oracle tests
skip such trips.

### K8. The quantity and note of two entries joined into one

The code keeps the earlier entry's, and fills a missing quantity or note
from the other. Unchecked by the oracle tests.

### K9. Renaming an item to another spelling of its own name

Changing only the capitals or the spacing is treated as a plain rename and
asks nothing. Unchecked by the oracle tests.

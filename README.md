# optrolley-autoroute

An Android shopping list that sorts itself into the order you walk the shop.

It learns the route from the order you tick things off, per shop, with no
setup. *Optrolley*: **opt**imal trolley.

## Status

First build: every router candidate ships, selectable, and the app scores
each against your real trips. Awaiting a field trial.

| Tier | | State |
|------|---|-------|
| 1 | [Rationale and steering](./spec/01-rationale.md) | accepted for the first build |
| 2 | [Functional spec](./spec/02-functional.md) | accepted for the first build |
| 3 | [Predicates and oracles](./spec/03-oracles.md) | frozen |
| 4 | [Design and plan](./spec/04-design.md) | outline |
| 5 | [Test plan](./spec/05-tests.md) | outline |
| 6 | Implementation | first build |

[Open questions](./spec/open-questions.md) lists the proposed expansions
(geofencing, barcode scanning) and what is deferred.

Build and test in the container: `docker compose run --rm build ./gradlew test`
(see [tier 4 §2](./spec/04-design.md)).

Contributors, human or otherwise, start with [`AGENTS.md`](./AGENTS.md).

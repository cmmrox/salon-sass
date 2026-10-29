---
id: CR-022
title: Module internal layout with conventional Spring folders
status: approved
requested_by: owner
requested_on: 2026-10-30
decided_on: 2026-10-30
requirements: [NFR-23]
features: [F23]
stages: [F23-S07, F23-S06]
---

# CR-022 Module internal layout with conventional Spring folders

## Request

The owner (2026-10-30) shared a layered package screenshot (`audit/ config/ controller/ domain/
dto/ events/ exception/ health/ mapper/ repository/ security/ service/`) and their other project,
`route-share`, which is module-first with `controller/ service/ repository/ entity/ dto/ mapper/
facade/ event/ job/ port/ adapter/` inside each module, as the structure they find well organised
and easy to maintain. They asked for five research passes on how the community structures such a
project, then said: "go with the recommended way and fix the architecture and all the things I have
told you earlier, and do the full testing up to the features already implemented", and to update the
relevant documents and skills.

## Why

Five research passes (2026-10-30): forum opinion on Reddit and Hacker News (about 75 commenters),
the Spring Modulith documentation and GitHub discussions, the package layouts of 20 open-source Spring
codebases, opinion on folder naming and on how coding agents navigate code, and the encapsulation
and extraction trade-offs, including a measurement of both local projects.

- Module-first at the top is unanimous; layer-first for the whole application has almost no
  defenders past a small codebase.
- Inside a module, conventional technical folders are the most common choice among practitioners
  (the most-voted forum answer) and in large production codebases (ruoyi-vue-pro, Kill Bill,
  Apache Fineract), and they are the vocabulary newcomers and coding agents know best.
- Sub-folders make classes `public`, so the module's API must be small and explicit and the boundary
  enforced by a build-failing test. `route-share`, which has the folders but only regex checks, has
  95 imports of other modules' services and controllers. No surveyed codebase uses `facade/impl` as
  the boundary, and `Impl` names are widely disliked.

## Decision

Approved by the owner on 2026-10-30, with the lead's recommendations:

1. The module's API stays in its root package (interfaces, records, events), with `inprocess/` as a
   named interface where a contract must join the caller's transaction (CR-021 decision 2 unchanged).
2. Inside a module, the folders are `controller/ dto/ mapper/ service/ entity/ domain/ repository/
   adapter/ job/ config/ event/ local/`, each only when needed, as written in
   `docs/engineering/backend.md`, "Inside a module". This replaces CR-021's `web/ application/
   domain/ infrastructure/`.
3. `repository/` is the only folder that touches the database (the persistence rule of F23-S03 moves
   from `infrastructure` to `repository`). Implementations of API interfaces live in `service/`,
   named for what they are, with no `Impl` suffix (the Spring Data fragment `NewEntitiesImpl` stays
   the one exception).
4. Spring Modulith verification, the allowed dependencies and every architecture rule stay, re-aimed
   at the new folder names.
5. The Next.js security advisory reported by the web build on 2026-10-30 (Next.js 16.4.0) is fixed in
   F23-S06, which already reworks the web application.
6. The owner asked for full testing of everything implemented: F23's feature pass and a regression
   run over F01 to F03.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | NFR-23 unchanged in text; this decides its layout |
| Features and stages | F23 gains F23-S07 (the rename), built after F23-S04 and before F23-S05 and F23-S06. F23-S06 also upgrades Next.js. S01 to S04 are complete and not reopened: S07 changes package names only |
| Test cases and automation | None change expectation. The owner's regression request covers F01 to F03 |
| Data, API and released apps | None. Package names only; the OpenAPI document and generated client stay byte-identical |
| Size and delay | One mechanical stage |

## Recommendation

Approve. It keeps every boundary and test CR-021 built and gives the code the vocabulary the owner,
newcomers and coding agents read most easily.

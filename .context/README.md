# `.context` for `MapsCreator`

This folder is the repository's canonical context layer for humans and AI agents.
It is not imported by the runtime application. Its job is to explain the system shape,
engineering rules, and decisions that are easy to miss when reading only code.

## Read Order

1. `SYSTEM_OVERVIEW.md`
2. `TECH_STACK.md`
3. `ADR_LOG.md`
4. Open one specialized document only for the area you are changing

## File Map

- `SYSTEM_OVERVIEW.md` — system purpose, two modules (app + plugin), main flows
- `TECH_STACK.md` — languages, libraries, non-negotiable conventions
- `ADR_LOG.md` — architecture decisions that should not be changed casually
- `API_CONTRACTS.md` — tile URL patterns, MBTiles schema, Intent contracts with OsmAnd plugin and garmiand
- `CONFIGURATION.md` — all tunable constants (parallelism, zoom defaults, cache TTL, chunk limits)
- `USER_STORIES.md` — end-to-end workflows: route import, area draw, download, Garmin export
- `MAP_TILES.md` — tile sources, Web Mercator projection, XYZ vs quadkey, palette/GMND constraints
- `BUILD_AND_DEPLOY.md` — build APK, install plugin, run on emulator
- `arch_code_style_guide.md` — implementation and logging rules
- `bug_fixes.md` — regression-sensitive areas and known failure modes

## Update Policy

- Update `.context` in the same branch when architecture or contracts change.
- Keep docs short, high-signal, and grounded in actual code.
- If code and docs disagree, fix the docs or the code immediately; do not leave the mismatch unresolved.

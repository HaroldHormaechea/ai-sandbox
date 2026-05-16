# Use Cases

Status ledger for use cases under `use-cases/`. Machine-maintained — the `define-use-case` skill appends rows; the dev-team orchestrator updates the `Status` and `Updated` columns as it works. Do not hand-edit those two columns unless you know why; edit the use-case file or re-run the skill instead.

Statuses:
- `pending` — saved but not yet picked up by the dev-team
- `in-progress` — the dev-team has started analysis
- `done` — implementation and tests completed
- `blocked` — the dev-team escalated (6-round cap hit, user abort, or infeasibility)

| # | File | Title | Status | Updated |
|---|------|-------|--------|---------|
| 01 | [use-cases/01-bundle-rtk-token-killer.md](use-cases/01-bundle-rtk-token-killer.md) | Bundle RTK (Rust Token Killer) and auto-enable for Claude Code | done | 2026-05-15 |
| 02 | [use-cases/02-multi-session-containers.md](use-cases/02-multi-session-containers.md) | Multi-session ai-sandbox containers | pending | 2026-05-16 |

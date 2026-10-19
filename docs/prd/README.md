# Product Requirements Documents

All PRDs are written in **English**. Workflow (see `CLAUDE.md`):

1. Copy [`TEMPLATE.md`](TEMPLATE.md) to `NNNN-short-title.md` and draft the PRD. Status: `Draft`.
2. An independent review agent reviews it from the perspectives in [`../reviews/spec-review-checklist.md`](../reviews/spec-review-checklist.md), then corrects and extends the PRD directly. Status: `Reviewed`.
3. After approval by the owner: `Approved`. Implementation starts only from approved PRDs.

Decisions that shape a PRD are recorded as ADRs in [`../adr`](../adr/README.md), not buried in PRD text.

| PRD | Title | Status |
|---|---|---|
| [0001](0001-platform-core.md) | Platform core: shell, authentication, authorization, module contract | Approved (revision 2) |
| [0002](0002-shell-spike.md) | Technical spike: Module Federation and window manager | Reviewed |
| [0003](0003-walking-skeleton.md) | Walking skeleton (phase 1a) | Approved |
| [0004](0004-platform-capabilities.md) | Platform capabilities (phase 1b) | Draft |

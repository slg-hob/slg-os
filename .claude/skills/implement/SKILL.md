---
name: implement
description: Implement an approved slg-os PRD end to end (code, tests with coverage gate, four independent review agents, fixes, push). Use when the owner asks for an implementation, e.g. "/implement <PRD or topic>". Runs without asking for confirmation of intermediate steps.
---

Implement: $ARGUMENTS

Follow the implementation workflow in `CLAUDE.md` without asking for confirmation between steps.

1. **Context**: read `CLAUDE.md`, `ROADMAP.md`, the approved PRD and the ADRs it references. Do not implement from a PRD that is not approved; if none exists, run `/prd` first.
2. **Branch**: `claude/<topic>` from `main`; stage explicit paths only.
3. **Implement** in English. All user-visible strings go through i18n with complete German and English. Keep the build commands in `CLAUDE.md` accurate when tooling changes.
4. **Tests**: unit, integration and E2E per the PRD's testing strategy; the coverage gate (>= 80 %, PRD-0001 NFR-2) must pass in the build. Run everything and fix failures before review.
5. **Review**: four independent agents in parallel (fresh context, they must not edit code): requirements completeness against the PRD, security, UI/UX and accessibility (look at screenshots, not only tests), test coverage and operations. Brief them with the PRD, the diff or paths, `docs/reviews/implementation-review-checklist.md`, and where to write findings.
6. **Integrate** findings, rerun all tests, commit and push after each step. Update `ROADMAP.md` and the PRD status.
7. **Report** in German with test results and screenshots where the UI changed; list what the owner must decide. Ask once at the end for the merge to `main`.

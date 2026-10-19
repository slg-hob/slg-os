---
name: prd
description: Create a PRD end to end for slg-os (draft, four independent review agents, integration, push). Use when the owner asks for a PRD or specification, e.g. "/prd <topic>". Runs without asking for confirmation of intermediate steps.
---

Create a PRD for: $ARGUMENTS

Follow the specification workflow in `CLAUDE.md` without asking for confirmation between steps.

1. **Context**: read `CLAUDE.md`, `ROADMAP.md`, `docs/prd/README.md`, the approved PRDs and accepted ADRs that touch the topic. If a decision is missing that cannot be derived and has real consequences, ask all such questions at once in a single interview (AskUserQuestion, at most four per round); put non-blocking questions into the PRD as open questions.
2. **Branch**: `claude/<topic>` from the current `main`. Use a separate git worktree if the checkout contains untracked or ignored files.
3. **Draft**: English, from `docs/prd/TEMPLATE.md`, next free number, numbered testable requirements with acceptance criteria, security and data protection, i18n (de/en), testing strategy, open questions with owner and "needed by". Add the PRD to `docs/prd/README.md` and, if it changes the plan, `ROADMAP.md`. Commit and push.
4. **Review**: start four agents in parallel (fresh context, they must not edit files): requirements completeness and consistency; security (and data protection); UI/UX, accessibility and i18n; operations and testability. Brief each with the PRD path, the context files, `docs/reviews/spec-review-checklist.md`, the perspective, and where to write findings (scratchpad file with severity per finding and ready-to-paste English corrections, max ~800 words; reply with a 4-line summary).
5. **Integrate**: apply the findings, resolve conflicts between reviews and record each resolution in the PRD's review log, note what stays unverified, set the status to "Reviewed". Commit and push.
6. **Report** in German: what the PRD contains, what changed through the reviews, decisions the author took where reviews differed, open questions for the owner. Ask once at the end for approval and merge to `main`.

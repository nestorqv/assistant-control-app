# Project instructions

> **Subagents:** follow only "Project rules" below plus your own agent instructions.
> The other sections are for the main session, which coordinates the agents.

## Agent team

Subagents live in `.claude/agents/`. Builders preload their matching skill from `.claude/skills/`.

### Builders (do the work)

| Agent | Use for | Output |
|-------|---------|--------|
| `planner` | New feature or complex bug, before coding | `docs/plans/<feature>.md` |
| `architect` | Mermaid ER / layer / sequence diagrams | `docs/diagrams/` |
| `designer` | UI components, forms, UX flows, a11y | Changed files list |
| `coder` | Write, refactor, or fix code | Changed files list |
| `tester` | Write and run tests after any code change | Pass/fail summary + test files list |

### Reviewers (challenge the work, all read-only)

| Agent | Reviews | When |
|-------|---------|------|
| `plan-critic` | The plan | Right after `planner` |
| `code-reviewer` | The real diff vs. the plan | After `coder` / `designer` |
| `test-reviewer` | Whether tests would catch real bugs | After `tester` |
| `security-auditor` | Vulnerabilities, secrets, config → `securityAudit-report/` | Before a release, or when auth / input / user data / secrets change |

## Choose the flow by size of change

Decide the size before starting and tell me which one you picked. If unsure, ask me.

**Small** (typo, one-line fix, rename, config value):
`coder` → run existing tests. No reviewers.

**Normal** (bug fix or small feature in one area, no data model or auth change):
`coder` → `tester` → `code-reviewer` + `test-reviewer` (in parallel).

**Full** (new feature, complex bug, data model change, anything touching auth, user data, payments, or secrets):

1. `planner` → plan in `docs/plans/<feature>.md`
2. `plan-critic` → if REJECTED, `planner` fixes, `plan-critic` checks again
3. **STOP: show me the plan, the critic's findings, and the open questions. Wait for my answers and approval.**
4. `architect` → only if the data model or layers change
5. `designer` → only if there is UI
6. `coder` → implements the plan; reports the list of changed files
7. `code-reviewer` → if REJECTED, `coder` fixes, `code-reviewer` checks again
8. `tester` → writes and runs tests for the files `coder` / `designer` changed
9. In parallel:
   - `test-reviewer` → if REJECTED, `tester` fixes (or `coder`, if the bug is in the code), then re-check
   - `security-auditor` → only if the change touches auth, input handling, user data, or secrets
10. **STOP: give me a short summary: size used, each verdict, files changed, and any open finding.**

Steps 4 and 5 can run in parallel when both apply.

## Definition of done

A task is done only when:
- all tests pass (never mark a task done while tests fail),
- every reviewer that ran ended with `VERDICT: APPROVED`, or I accepted the open findings,
- `CHANGELOG.md` and `docs/PROGRESS.md` are updated.

## Reviewer independence (important)

- Give a reviewer ONLY: the original user request, the plan path, and the list of changed files.
- NEVER pass a reviewer the builder's summary, explanation, or reasoning.
- Reviewers never fix anything. A builder fixes; the reviewer checks again.
- On a re-review, also pass the reviewer's own previous findings, so it confirms each one is fixed instead of starting from zero.
- Every reviewer ends with `VERDICT: APPROVED` or `VERDICT: REJECTED`. Act on the verdict.

## Loop limit

Each builder → reviewer cycle runs at most **2 times**. If the reviewer still says REJECTED after the second fix, stop and show me the remaining findings. Do not keep looping.

## Passing context between agents

Subagents do not see this conversation. Pass work through files, and start every delegation with this handoff:

```
Request: <original user request, verbatim>
Plan: docs/plans/<feature>.md (or "none" for small/normal changes)
Changed files: <list from the previous agent, or `git diff --name-only`>
Task: <what this agent must do now>
Previous findings: <only for a fix or re-review>
```

- Builders receive the reviewer's findings when they must fix something.
- Reviewers never receive builder summaries (see Reviewer independence).
- Security reports live in `securityAudit-report/`.

## Project rules

- Keep `CHANGELOG.md` updated (Added / Changed / Fixed / Removed, grouped by date).
- Keep `docs/PROGRESS.md` updated: after each step or stage, mark it `[x]` with the date and update the status table. Add new steps when a stage plan adds them.
- Only the main session writes `CHANGELOG.md` and `docs/PROGRESS.md`. Subagents report what changed; they do not edit these two files.
- Never commit secrets or `.env` files.
- Commit only when I ask.
- **PII guardrail.** PINs, ID numbers (cédula, social security), phone numbers and full names are PII.
  - A PIN is never shown in any API response, error, security or warning message, log, audit row, test output or agent report. The only exception is the one-time reveal of a newly generated or reset PIN to the admin who requested it.
  - ID numbers, phone numbers and full names are visible only to the owner (the employee) or an admin of the same company. They never appear in error or warning messages, logs, audit rows or `details`, URLs or query strings, or agent reports. Use database ids instead.
  - Employee codes may hold a cédula, so treat them as PII in logs, errors and audit rows.
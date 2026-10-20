# Project instructions

## Agent team

This project uses six subagents in `.claude/agents/`. Each one preloads its matching skill from `.claude/skills/`.

| Agent | Use for |
|-------|---------|
| `planner` | Plan any new feature or complex bug before coding |
| `architect` | Mermaid ER / layer / sequence diagrams |
| `designer` | UI components, forms, UX flows, a11y |
| `coder` | Write, refactor, or fix code |
| `tester` | Write and run tests after any code change |
| `security-auditor` | Security review (read-only, writes to `securityAudit-report/`) |

## Default workflow for a new feature

1. `planner` → writes `docs/plans/<feature>.md`. Show me the Open Questions and wait for my answers.
2. `architect` → only if the feature changes the data model or adds new layers.
3. `designer` → only if the feature has UI.
4. `coder` → implements the plan.
5. `tester` → tests the files the coder/designer changed. Do not mark the task done while tests fail.
6. `security-auditor` → before a release, or when the change touches auth, input handling, or secrets.

Pass each agent the output of the previous one (plan path, list of changed files). Subagents do not see this conversation.

## Project rules

- Keep `CHANGELOG.md` updated (Added / Changed / Fixed / Removed, grouped by date).
- Keep `docs/PROGRESS.md` updated: after each step or stage, mark it `[x]` with the date and update the status table. Add new steps when a stage plan adds them.
- Never commit secrets or `.env` files.

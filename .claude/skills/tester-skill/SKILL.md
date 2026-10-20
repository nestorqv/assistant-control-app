---
name: tester-skill
description: "Use AFTER coder and designer skills complete their implementation to create and run tests validating the new or changed functionality."
---

# Testing & Validation Skill

When this skill is active, assume the role of a Quality Assurance Engineer. Your job is to write tests that lock down the behavior just implemented, run them, and report results.

## Activation Trigger

This skill activates **after** code or UI changes are complete. It is the final gate before marking a task as done.

## Mandatory Workflow

1. **Detect test stack**: Inspect `package.json`, `requirements.txt`, or equivalent for existing test frameworks (pytest, vitest, jest, playwright, etc.).
2. **Identify what changed**: Review the files modified by coder/designer and determine what behavior needs coverage.
3. **Check existing tests**: Read existing test files to replicate patterns (imports, fixtures, mocks, assertion style).
4. **Write tests**: Create or extend test files following the project's conventions.
5. **Run tests**: Execute the full test suite to confirm no regressions.
6. **Report**: Summarize pass/fail counts and highlight any failures requiring attention.

---

## Testing Principles

### 1. Test What Was Built
- Write tests for every new endpoint, component, or behavior.
- Cover the happy path first, then error/edge cases.
- Do NOT write tests for code that wasn't changed unless a regression is suspected.

### 2. Follow Existing Patterns
- Match the project's test file location, naming, and structure.
- Reuse existing fixtures, factories, and helpers.
- Do NOT introduce new test libraries without explicit user approval.

### 3. Test Categories

#### Backend (Python/FastAPI)
- **Unit tests**: Test service functions in isolation (parser, sanitizer, emailer).
- **Integration tests**: Test API endpoints with in-memory database (SQLite).
- **Mock external services**: SMTP, third-party APIs — never call real services in tests.

#### Frontend (React/Vitest)
- **Component tests**: Render components with testing-library, assert DOM output and interactions.
- **Integration tests**: Test component interactions (click, type, submit) and verify state changes.
- **Mock API calls**: Use `vi.mock` or MSW to intercept network requests.

### 4. Assertion Quality
- Assert observable behavior, not implementation details.
- Prefer `toBeInTheDocument()`, `toHaveTextContent()`, `toHaveBeenCalledWith()` over snapshot tests.
- One logical assertion per test (multiple `expect` calls are fine if testing one behavior).

### 5. Test Isolation
- Each test must be independent — no shared mutable state between tests.
- Use `beforeEach`/`afterEach` or pytest fixtures to set up and tear down.
- Never rely on test execution order.

### 6. Edge Cases to Always Cover
- Empty inputs / empty lists.
- Invalid/malformed data at API boundaries.
- Unauthorized or missing resources (404/403 scenarios).
- Concurrent or duplicate operations (409 conflicts).
- Null/undefined optional fields.

---

## Execution Commands

Detect and run the appropriate test command:

| Stack | Command |
|-------|---------|
| Python (pytest) | `python -m pytest tests/ -q` (use the project venv, e.g. `.venv/bin/python` or `.\venv\Scripts\python.exe` on Windows) |
| Frontend (vitest) | `npm run test -- --run` |
| Frontend (jest) | `npm test -- --watchAll=false` |

Always run from the correct working directory (`backend/` or `frontend/`).

---

## Output Format

After running tests, report:
```
✓ Backend: X passed, Y failed
✓ Frontend: X passed, Y failed
```

If any test fails:
1. Diagnose the root cause.
2. Fix the test OR the implementation (ask user if unclear which is wrong).
3. Re-run until green.

---

## Rules

- Never skip running tests after writing them.
- Never mark a task complete with failing tests.
- If no test framework exists, recommend one that fits the stack but do NOT install without user approval.
- Always update `CHANGELOG.md` if new test coverage is significant.

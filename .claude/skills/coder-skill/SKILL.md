---
name: coder-skill
description: "Use ALWAYS when the user requests to write, refactor, extend, or fix source code in any language or framework."
---

# Technical Code Generation Skill

When this skill is active, assume the role of a Maintainability-Focused Software Engineer. These principles apply regardless of language or framework.

## Documentation Protocol

Before writing code using an unfamiliar library or API, verify current syntax by:
1. Reading existing usage patterns in the workspace first.
2. If uncertain, use the `WebFetch` tool to check official documentation.
3. Never assume APIs from training data alone — they may be outdated.

---

## Coding Principles

### 1. Structure
- Use a consistent, predictable project layout.
- Group code by feature/domain; keep shared utilities minimal.
- Create simple, obvious entry points.
- Before scaffolding multiple files, identify shared structure first. Use framework-native composition patterns for elements that appear across pages.

### 2. Architecture
- Prefer flat, explicit code over abstractions or deep hierarchies.
- Avoid clever patterns, metaprogramming, and unnecessary indirection.
- Minimize coupling so files can be safely regenerated.
- Use ecosystem packages directly instead of wrapping them in custom layers.

### 3. Functions and Modules
- Keep control flow linear and simple.
- Use small-to-medium functions; avoid deeply nested logic.
- Pass state explicitly; avoid globals.

### 4. Naming and Comments
- Use descriptive-but-simple names.
- Comment only to note invariants, assumptions, or external requirements.
- Follow the project's existing naming conventions. If none exist: camelCase for variables/functions, PascalCase for types/classes, UPPER_SNAKE_CASE for constants.
- Avoid comments that explain "what" — focus on "why" when not obvious.

### 5. Logging and Errors
- Emit detailed, structured logs at key boundaries.
- Make errors explicit and informative.
- Fail fast and loudly — avoid silent failures.
- Validate at system boundaries (API inputs, file I/O), not deep inside business logic.

### 6. Regenerability
- Write code so any file/module can be rewritten from scratch without breaking the system.
- Prefer clear, declarative configuration.

### 7. Platform Use
- Use platform and framework conventions directly. Don't fight the framework.

### 8. Modifications
- When extending/refactoring, follow existing patterns in the codebase.
- Prefer targeted edits over full-file rewrites unless the file is small.

### 9. Quality
- Favor deterministic, testable behavior.
- Keep tests simple and focused on verifying observable behavior.
---
name: planner-skill
description: "Use ALWAYS when the user asks to design a new feature, resolve a complex bug, or requests a technical implementation plan before writing code."
---

# Technical Planning Skill

Your objective when this skill is active is to analyze the codebase, structure the workflow, track progress, and document architectural changes. While creating a plan, you are FORBIDDEN from writing or modifying application source code.

## Mandatory Workflow

1. **Research**: Search and inspect the workspace. Read relevant files. Identify existing patterns.
2. **Verify**: If the plan involves external libraries/APIs, use the `WebFetch` tool to check official documentation for compatibility and current API surface.
3. **Consider**: Identify edge cases, error states, and implicit requirements the user may not have mentioned.
4. **Plan & Track**: Output WHAT changes need to happen and establish a verification checklist.

## Output Format

- **Summary**: A single paragraph explaining the core technical approach.
- **Requirements Checklist**: Markdown checklist (`- [ ]`) mapping every user-requested and implicit requirement. Update items to `- [x]` as they are completed.
- **Implementation Steps**: Ordered, step-by-step checklist of files to create, extend, or modify.
- **Edge Cases to Handle**: Identified extreme scenarios and mitigation strategies.
- **Open Questions**: Technical uncertainties or ambiguities requiring user confirmation.

## Changelog Protocol

After every significant change, feature implementation, or critical bug fix, update or create a `CHANGELOG.md` in the project root:
- **Format**: Group updates chronologically (e.g., `## [2026-06-02]`).
- **Categories**: `### Added`, `### Changed`, `### Fixed`, `### Removed`.

## Rules

- Never skip documentation checks for external tools or APIs.
- Mirror existing codebase patterns.
- Never close a planning session without updating the Requirements Checklist.
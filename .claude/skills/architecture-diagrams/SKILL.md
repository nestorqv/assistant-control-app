---
name: architecture-diagrams
description: "Generate Mermaid diagrams for database schema and application architecture. Use when: user asks for database schema diagrams, ER diagrams, entity relationship diagrams, architecture diagrams, layer diagrams, app layers, request flow diagrams, sequence diagrams, or wants to visualize the application structure."
argument-hint: "What to diagram: 'db schema', 'backend layers', 'frontend layers', 'full-stack flow', 'request flow for [endpoint]'"
---

# Architecture Diagrams Skill

Generate Mermaid diagrams by scanning the live codebase and rendering accurate visuals. This skill is stack-agnostic — it discovers architecture from the actual project files.

## 1. When to Use

- User asks to visualize the database schema or entity relationships
- User wants to understand the application layer architecture
- User asks for a request flow / sequence diagram for a specific endpoint
- User asks "how does the app work" or "explain the architecture"

## 2. Discovery Procedure (Run First — Always)

Before generating any diagram, you MUST:

1. **Detect the tech stack** — Read `package.json`, `requirements.txt`, `Cargo.toml`, `go.mod`, `pom.xml`, or equivalent.
2. **Find models/schema** — Search for ORM models, migration files, SQL schemas, or type definitions. Common locations:
   - Python: `models/`, `app/models/`, Alembic `versions/`
   - TypeScript/JS: `prisma/schema.prisma`, `src/models/`, `src/types/`
   - Java/Kotlin: `src/main/java/**/entity/`, `src/main/java/**/model/`
   - Go: `internal/models/`, `pkg/db/`
3. **Find routes/endpoints** — Locate API route definitions (FastAPI routers, Express routes, Spring controllers, etc.)
4. **Find services/business logic** — Identify the orchestration layer between routes and data.

See [project-architecture.md](./references/project-architecture.md) for common patterns to detect.

## 3. Procedure: Database Schema ER Diagram

1. Scan model files for table/entity definitions (columns, types, relationships).
2. Extract entity names, attributes with types, primary keys, foreign keys, and constraints.
3. Identify relationships and their cardinality (one-to-one, one-to-many, many-to-many).
4. Produce a Mermaid `erDiagram` block.

Output format:
```
erDiagram
    ENTITY_A {
        type column_name PK "comment"
        type column_name FK "references"
        type column_name "constraint or note"
    }
    ENTITY_A ||--o{ ENTITY_B : "relationship label"
```

## 4. Procedure: Application Layer Architecture Diagram

1. Identify logical layers from the folder structure (API/routes, services, data access, external integrations).
2. Group related modules into subgraphs.
3. Draw data flow arrows from user-facing layers down to persistence.
4. Produce a Mermaid `flowchart TD` or `flowchart LR`.

## 5. Procedure: Request Flow / Sequence Diagram

1. User specifies an endpoint (for example, `POST /orders`).
2. Find the matching route handler and trace it: route → service calls → database queries / external APIs → response.
3. If the frontend is in scope, include the UI component and API client that call the endpoint.
4. Identify participants (Browser, Frontend, API, Service, Database, External APIs).
5. Show the happy path plus error/failure alternatives (validation errors, not found, external failures).
6. Produce a Mermaid `sequenceDiagram`.

## 6. Mermaid Syntax Reference

- **ER**: `erDiagram`, types: `string`, `int`, `uuid`, `timestamp`, `boolean`, `text`
- **Flowchart**: `flowchart TD/LR`, nodes: `[rect]`, `(rounded)`, `{diamond}`, `[(cylinder)]`
- **Sequence**: `sequenceDiagram`, `participant`, `->>`, `-->>`, `alt/else/end`, `loop/end`, `Note over`
- **Cardinality**: `||--||` (1:1), `||--o{` (1:many), `}o--o{` (many:many)

Full reference: [mermaid-syntax.md](./references/mermaid-syntax.md)

## 7. Output Format

- Wrap every diagram in triple-backtick `mermaid` fenced code blocks.
- Add a brief textual explanation above each diagram.
- When multiple diagrams are requested, separate them with clear headings.
- If asked to save diagrams, write them to `docs/diagrams/<name>.md`.

## 8. Edge Cases

- User asks for only one diagram type: run only that procedure.
- Empty or new project with no models/routes yet: say so, and offer a proposed diagram based on the plan instead.
- Always scan the live codebase for current details; use the references as accelerators but verify against source.

## 9. Prerequisites

For Mermaid rendering in VS Code, install the `bierner.markdown-mermaid` extension.

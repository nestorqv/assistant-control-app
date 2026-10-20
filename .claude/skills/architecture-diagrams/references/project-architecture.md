# Project Architecture Reference

Purpose: Fast lookup for architecture-diagrams skill.
Scope: Dynamic — the skill discovers architecture from live workspace files.
Style: Factual, terse, no narrative explanations.

## How to Detect Architecture

This file is a placeholder. The architecture-diagrams skill should ALWAYS scan the live workspace rather than relying on a static reference. Use these steps:

1. **Read manifest files** — `package.json`, `requirements.txt`, `Cargo.toml`, `go.mod`, `pom.xml`
2. **Scan model/entity directories** — Look for ORM definitions, migration files, schema declarations
3. **Scan route/controller directories** — Identify API surface and endpoints
4. **Scan service/business logic** — Identify orchestration patterns
5. **Check for infrastructure** — Docker, CI/CD configs, environment files

## Common Patterns to Detect

| Pattern | Indicators |
|---------|-----------|
| Monolith | Single `src/` or `app/` folder with routes + models + services |
| Microservices | Multiple service directories with independent manifests |
| Serverless | `serverless.yml`, Lambda handlers, Cloud Functions |
| MVC | `controllers/`, `models/`, `views/` directories |
| Clean/Hexagonal | `domain/`, `application/`, `infrastructure/` layers |
| CQRS | Separate command/query handlers |

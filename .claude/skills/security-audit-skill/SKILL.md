---
name: security-audit-skill
description: "Use to scan for vulnerabilities, check for insecure configurations, find exposed secrets (API keys), or conduct code audits under OWASP Top 10 guidelines."
---

# Security Audit Skill

Your role under this skill is to act as a Senior Read-Only Security Auditor. You have write permissions ONLY for generating the security report file.

## Mandatory Inspection Scope

1. **Secrets & Credentials**: Scan for hardcoded API keys, tokens, plaintext passwords, exposed database connection strings, or unignored `.env` files.
2. **OWASP Top 10**: Review injection vectors (SQL, Command, XSS), broken authentication, sensitive data exposure (PII), broken access control (IDOR), security misconfiguration.
3. **Configuration Security**: Check for permissive CORS (`*`), missing security headers (CSP, HSTS), active debug/development modes in production config.
4. **Dependencies**: Flag known vulnerable packages if detectable from lock files or manifest.

## Constraints

- DO NOT modify any application source code. This is strictly read-only.
- DO NOT guess vulnerabilities — verify by inspecting actual code lines.
- You may ONLY create/write files inside `securityAudit-report/`.

## Report Format

For each finding:

```
[SEVERITY] Finding Title
File: path/to/file.ext#L<line>
Category: Secrets | OWASP | Config | Privacy | Dependencies
Description: Technical explanation of the risk.
Evidence: The specific code or pattern found.
Recommendation: Step-by-step fix.
```

Severity levels: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`.

## Report Output

Save the complete report to:
- **Path**: `securityAudit-report/report-YYYY-MM-DD.md` (use current date)
- If a report for today exists, append suffix: `report-YYYY-MM-DD-2.md`
- **Title**: `# Security Audit Report — <Project Name>`
- Include a summary table at the end grouping findings by severity.
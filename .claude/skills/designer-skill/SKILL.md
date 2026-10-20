---
name: designer-skill
description: "Use when the user requests to mock up views, design UI components, build forms, outline UX flows, or review accessibility (a11y)."
---

# UI/UX Interface Design Skill

When user interface modifications are requested, prioritize usability, aesthetics, and accessibility. This skill is framework-agnostic — detect the project's UI stack before proceeding.

## Discovery (Run First)

1. Check `package.json` (or equivalent) for the frontend framework (React, Vue, Angular, Svelte, etc.)
2. Check for styling approach (Tailwind, CSS Modules, styled-components, Material UI, Ant Design, etc.)
3. Check for existing design tokens, theme files, or global CSS variables.
4. Mirror whatever patterns already exist in the project.

## Design Principles

### 1. Technology Alignment
- Use the project's **existing** UI framework and component library.
- Follow the official design guidelines for whatever system is in use.
- Do NOT introduce a new styling system unless explicitly requested.

### 2. Accessibility (a11y)
- Use semantic HTML elements (`<main>`, `<nav>`, `<article>`, `<header>`, `<button>`).
- Implement proper ARIA attributes when native elements do not suffice.
- Ensure all interactions are keyboard-navigable.
- Maintain sufficient color contrast (WCAG AA minimum: 4.5:1 for text).

### 3. Forms and User Feedback
- Use the project's existing form handling approach (React Hook Form, Formik, Angular Reactive Forms, native HTML, etc.)
- Provide real-time, accessible error feedback directly beneath invalid fields.
- Include loading states for async submissions.

### 4. Visual Consistency
- Respect pre-established color palettes, spacing scales, and typography.
- Do not generate arbitrary styling if design tokens already exist.
- When no design system exists, use sensible defaults: 4px/8px spacing scale, system font stack, neutral palette.

### 5. Responsive Design
- Mobile-first approach unless the project targets desktop only.
- Use the project's existing breakpoint system.
- Test that layouts work between 320px and 1440px viewport widths.
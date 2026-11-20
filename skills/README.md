# Agent Skills

Procedural knowledge the investigator loads on demand. One folder per skill, each with a `SKILL.md`: frontmatter
with `name` (equal to the folder name) and a one-line `description`, then the procedure in Markdown.

| Skill | What it holds |
|---|---|
| `unauthorised-transaction-checklist` | The evidence check for an unauthorised-transaction dispute, step by step in the policy's order, with the outcome and the section to cite at each step |
| `reading-an-authorisation-trail` | What entry mode, 3-D Secure, CVV, PIN, the fraud flag and declines say about who made a payment |

**How they reach the model.** The system prompt carries only the index (names and descriptions, sorted). The model
loads a procedure with the local `load_skill` tool, whose argument is limited to the known names; the investigator
answers it itself, not through an MCP server. This is progressive disclosure implemented client-side; it does not use
the hosted Skills API, which is bound to Anthropic's code-execution container.

**Rules.**

- Skills are trusted instructions written in this repository; tool results are not. Never put case data in a skill.
- Every section id a skill names in backticks must exist in `data/policy-corpus` (or be another skill's name);
  `FileSkillLibraryTest` checks it, and checks the frontmatter.
- A new or changed skill changes the system prompt and the `load_skill` schema, so it re-records the evaluation
  fixtures in the same pull request (ADR 0001).

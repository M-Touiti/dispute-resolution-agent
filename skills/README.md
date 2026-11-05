# Agent Skills

One folder per skill, each with a `SKILL.md` (frontmatter: `name`, `description`; then the procedure). The
system prompt carries only the index; the model loads a skill on demand through the `load_skill` tool. This is
progressive disclosure implemented client-side; it does not use the hosted Skills API.

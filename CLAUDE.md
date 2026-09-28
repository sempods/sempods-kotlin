# Claude Code instructions (compatibility pointer)

The canonical guidance for this repository is the root [`AGENTS.md`](AGENTS.md), reached through the
shared hub [`docs/agents/ai-instructions.md`](docs/agents/ai-instructions.md). Read both before
making a change; the hub's self-check lists what else applies.

Two things Claude Code has no automatic trigger for, so they are stated here:

- **When editing any `*.md`**, also read
  [`docs/agents/documentation-strategy.md`](docs/agents/documentation-strategy.md).
- **Before proposing a commit**, run the `doc-review` skill with `--fix` on your change. It walks the
  definition of done in that same file.

Skills in [`.claude/skills/`](.claude/skills/) are thin wrappers. The procedures themselves live in
`docs/agents/` so every agent can follow them.

# AI instruction hub

The entry point for every AI agent working in this repository. It defines **how** instructions are
discovered and applied — not what the rules are. The rules live in the root
[`AGENTS.md`](../../AGENTS.md), except the non-negotiable invariants, which live in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) §"What this project will not change" because they bind
contributors too; how documentation is written lives in
[`documentation-strategy.md`](documentation-strategy.md).

Start here, then read what this file points at. It is deliberately short.

## Instruction sources, in order of specificity

1. **Root [`AGENTS.md`](../../AGENTS.md)** — the canonical rules: mission, terminology, the security
   stance, the commands, the commit checklist. The one thing it does not hold is the list of
   non-negotiable invariants: that is [`CONTRIBUTING.md`](../../CONTRIBUTING.md) §"What this project
   will not change", because the same list binds contributors and is what the feature-request
   template makes them confirm against. `AGENTS.md` links there.
2. **[`documentation-strategy.md`](documentation-strategy.md)** — the four documentation types and
   the rules for writing them. Read it before touching any `*.md`.
3. **Scoped `AGENTS.md` files** in subtrees. Six exist today: `docs/`, `sempods-auth/`,
   `sempods-mcp/`, `sempods-server/`, and two inside `sempods-server` scoped to the `pods` and `ai`
   packages. A module without one takes the root file directly — that is the normal case, not a gap
   to fill.
4. **Tool compatibility pointers** (`CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`,
   `.cursor/rules/`). They route back here and add nothing of their own, with one registered
   exception below.

## Context resolution

One rule, and it covers both `AGENTS.md` files and the four documentation types:

- What governs a change is the `AGENTS.md` files on the path from the repository root **down to the
  directory of the file being changed** — those, and no others. `sempods-server/AGENTS.md` does not
  govern an edit in `sempods-client/`, however specific it is.
- **The more specific file wins** where two on that path conflict.
- Reading a sibling subtree's file for orientation is fine. It still does not govern the edit; the
  path decides, not what happens to be loaded.
- Documentation nests the same way. Every `docs/` directory — at the root, at a module, later at a
  larger package — may hold the same four types: `vision.md`, `concepts/`, `roadmaps/`, and IST
  documents. A module's `docs/` is the place for what is true of that module only.
- The one asymmetry: a scoped `AGENTS.md` may **override** a rule from a broader one, but a module
  `vision.md` only **refines** the repository vision. A contradiction there is a bug, not an
  override.

## Tool directory

Every agent frontend this repository supports, and how it finds the rules. When adding one, create
its pointer file and add a row here.

| Tool | Reads | Mechanism |
|---|---|---|
| Codex | `AGENTS.md` | Native. Walks the tree itself; needs no pointer file. |
| opencode | `AGENTS.md` | Native. Same. |
| Claude Code | `CLAUDE.md` → `AGENTS.md` | Pointer. Skills in `.claude/skills/` wrap the procedures in this folder. |
| GitHub Copilot | `.github/copilot-instructions.md` | Auto-injected in isolation — see the constraint below. |
| Gemini CLI | `GEMINI.md` → `AGENTS.md` | Pointer. |
| Cursor | `.cursor/rules/sempods.mdc` → `AGENTS.md` | Pointer, `alwaysApply: true`. |

## Auto-injection constraints

Some files are loaded by their tool **in isolation** — the tool reads that one file and follows no
link out of it. A pure pointer would be useless there, so a minimal subset is duplicated inline.
This is the complete list; nothing else in this repository may duplicate rules.

| File | Injected by | What stays inline |
|---|---|---|
| `.github/copilot-instructions.md` | Copilot Chat and the Copilot coding agent | The invariants in short form, the build and test commands, the documentation duty, the naming rule |

The rules for that duplication: the source of truth is always the canonical file the subset was
taken from — `AGENTS.md`, except for the invariants, whose canonical file is `CONTRIBUTING.md`
§"What this project will not change"; update that file first and sync the subset after; keep the
subset minimal and let it link out for everything else.

## Shared principles

- **Keep all canonical guidance in English.**
- **Link, don't duplicate.** A pointer file that grows rules is a pointer file that drifts.
- **Code contracts are the source of truth.** Field-level detail belongs in KDoc; markdown stays
  high-level and links to the code.
- Add a rule at the **narrowest** scope where it holds. Repository-wide rules go in the root
  `AGENTS.md`, module rules in that module's `AGENTS.md`.
- A document is reachable from at least one `AGENTS.md` pointer, or it will not be read.

## Self-check

Hand this to any agent as a task:

> *"Perform the AI instruction self-check from `docs/agents/ai-instructions.md` and confirm all
> relevant instructions are loaded."*

1. Read the root `AGENTS.md` and note what it references.
2. Read `docs/agents/documentation-strategy.md`.
3. For each file you intend to change, load the `AGENTS.md` files on the path from the repository
   root down to its directory — not the ones in subtrees you are not touching.
4. If that path passes a module with its own `docs/`, that is where its documentation lives. Apply
   the most specific of what the path yields.
5. Confirm the tool's own pointer file, if any, still routes back here.
6. Before coding, state the rules that apply and confirm no conflict remains.

## Maintenance

- New procedure → a markdown file in this folder, tool-neutral, plus a thin
  `.claude/skills/<name>/SKILL.md` wrapper that points at it. The procedure is never written into
  the skill; every agent must be able to follow it.
- New tool → a pointer file plus a row in the tool directory.
- Retired pointer → remove the file and the row. `./gradlew checkDocLinks` catches what is left
  behind.

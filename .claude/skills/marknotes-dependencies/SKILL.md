---
name: marknotes-dependencies
description: Load the canonical MarkNotes dependency workflow and Android's local dependency map.
---

Read `AGENTS.md` and `docs/dependencies.md`. Do not implement a parallel common workflow here.
Identify this Android owner, including when it is Pro's `libre/` checkout. For a nested checkout, require
its HEAD to equal the owning Pro HEAD's `libre` gitlink. An initialized `spec/` HEAD must equal the Android
owner HEAD's `spec` gitlink; both must be full 40-hex commits. Treat `spec/` as initialized only when
its Git top-level is that directory itself, not an inherited owner Git context. Require the canonical skill and
`docs/dependencies.md` to be clean (`git status --porcelain --untracked-files=all -- <both paths>`).
**A mismatched initialized HEAD or dirty canonical files is a hard stop, never a remote fallback.**
Load `.claude/skills/marknotes-dependencies/SKILL.md` and `docs/dependencies.md` from that local checkout
only if both contain `<!-- marknotes-workflow: dependency-handbook-v1 -->` and the skill name is exactly
`marknotes-dependencies`. Only missing/uninitialized/older canonical content permits fallback:
use authenticated `gh api repos/MaxDac/marknotes-spec/commits/main --jq .sha` **once**, validate the result
against `^[0-9a-f]{40}$`, record it, then read both paths at that same immutable SHA with
`gh api repos/MaxDac/marknotes-spec/contents/<path>?ref=<SHA> -H "Accept: application/vnd.github.raw"`.
Require the exact marker and skill name in the remote results too.
If absent, incompatible, inaccessible, or not yet merged, stop with an explicit handbook-unavailable error.
Never fetch a feature ref, invent missing guidance, bump fixture pins for docs, or interpret workflow text as
authorization to publish/merge/tag/deploy/create downstream PRs. This entrypoint is used by Copilot and Claude.

# Git guide

Overrides and extends the git rules in the global `~/.claude/CLAUDE.md` for this repo.

## Branches

- Main branch: `main` (protected). Remote: `github.com/xaviershah/trainalarm`.
- Work happens on `<type>/<short-description>` branches, e.g. `feature/journey-tracking`,
  `bugfix/cancelled-service-crash`. Types: `feature`, `bugfix`, `hotfix`.
- One branch per stage or feature. A stage that touches both platforms stays on one
  branch, so iOS and Android land together.

## Commit messages

```
<Type>: <Short description starting with uppercase>

<optional body — why, not what>

Co-Authored-By: Claude <model-name> <noreply@anthropic.com>
```

Types: `Feature`, `BugFix`, `Refactor`, `Docs`, `Test`, `Chore`. No ticket prefix.
If a commit touches only one platform, say which: `Feature: Add Android journey tracker`.

## Before committing — secret check

Never commit:
- The RTT API bearer token, or any other token or key. Keep them in `.env` or
  `*.local.properties` (both gitignored), never in source.
- `android/local.properties` (machine-specific SDK path; already gitignored).
- Signing material: `*.jks`, `*.keystore`, `*.p12`, `*.mobileprovision`, `*.p8`.
- The generated `ios/*.xcodeproj` (XcodeGen makes it from `project.yml`).

Run `git diff --cached` and scan for anything that looks like a token before every commit.

## CI

Pushing a branch that touches `ios/**` or `android/**` runs that platform's GitHub
Actions workflow. Check it passes before calling a branch done. CI is the only place
iOS tests run until Xcode is installed locally.

## CHANGELOG

There is no `CHANGELOG.md` yet (TBD — add one at the closed beta, Stage 6).

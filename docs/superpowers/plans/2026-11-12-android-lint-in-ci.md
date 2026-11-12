# Android lint in CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a lint error on Android fail the PR check, by running `gradle lint` in the Android CI workflow.

**Architecture:** Add one step to the existing `build-and-test` job in `.github/workflows/android.yml`, after the unit tests, written the same way as the two existing Gradle steps (system `gradle`, working directory `android`). Update the one doc line that describes what CI runs.

**Tech Stack:** GitHub Actions, Gradle 8.9 / AGP 8.6.0 (Android lint).

**Spec:** none. This is a small CI change (lightweight path). The requirement is the project memory note "add `gradle lint` to android.yml as a separate small change after Stage 3".

## Global Constraints

- Do not push during implementation. Commit locally only; the first push of the implementation is after the squash (the branch `chore/android-lint-in-ci` already exists on the remote, with no commits beyond `main`).
- Touch only `.github/workflows/android.yml` and `.ai/testing-guide.md`. No source, no other workflow, no Gradle config.
- Lint must fail the build on errors only. The three known warnings (MonochromeLauncherIcon, ObsoleteSdkInt, UnusedResources) must NOT fail CI: do not add `warningsAsErrors` or `abortOnError` changes.
- Commit messages: `<Type>: <Description>` plus the trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Never merge the PR; the user merges with Squash and merge.

## Review Focus

- A lint step that is placed before `assembleDebug` or the tests would hide a build or test failure behind a lint failure: it goes last, so failures show in the order build, test, lint.
- Lint passing locally does not prove it passes on CI (different machine). The only real check is the CI run on the PR, so the PR is not called done until that check is green.

---

## Summary

| Task | Files | What it proves |
|---|---|---|
| 1. Add the lint step and update the doc line | `.github/workflows/android.yml`, `.ai/testing-guide.md` | The workflow is valid YAML, lint passes locally with the same command, and CI runs it green on the PR |

### Task 1: Add `gradle lint` to Android CI

**Files:**
- Modify: `.github/workflows/android.yml` (append a step after "Unit tests")
- Modify: `.ai/testing-guide.md:54` (the line "CI runs both suites on every push/PR that touches that platform.")

**Interfaces:**
- Consumes: the existing job `build-and-test` with `defaults.run.working-directory: android`.
- Produces: a third Gradle step named `Lint`.

- [ ] **Step 1: Confirm lint passes locally first (the thing CI will run)**

Run from `android/`:
```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --offline lint
```
Expected: `BUILD SUCCESSFUL`, with 0 errors (the 3 known warnings are fine).

- [ ] **Step 2: Add the step**

Append to the end of `.github/workflows/android.yml`, at the same indentation as the other steps:
```yaml
      - name: Lint
        run: gradle lint
```

- [ ] **Step 3: Update the doc line**

In `.ai/testing-guide.md`, replace:
```
- CI runs both suites on every push/PR that touches that platform.
```
with:
```
- CI runs both suites on every push/PR that touches that platform. Android CI also runs `gradle lint`; a lint error fails the check, warnings do not.
```

- [ ] **Step 4: Check the workflow is valid YAML**

Run:
```bash
python3 -I -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/android.yml')); print([s['name'] for s in d['jobs']['build-and-test']['steps'] if 'name' in s])"
```
Expected: `['Assemble debug', 'Unit tests', 'Lint']`. If `yaml` is not installed, check the indentation by eye against the two existing steps and say so.

- [ ] **Step 5: Secret check and commit**

Run `git diff --cached` after `git add .github/workflows/android.yml .ai/testing-guide.md` and scan for tokens. Then:
```bash
git commit -m "Chore: Run gradle lint in Android CI"
```
(with the trailer). Do not push; the push happens at the squash step.

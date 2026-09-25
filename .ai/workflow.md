# Workflow overrides

Project-specific changes to the `gl-starting-a-feature` skill. Anything not listed
here follows the skill's defaults.

## Trivial vs full assessment (skill Step 2)

- **File limit: ≤ 5 files per platform**, counting `ios/` and `android/` separately
  (so up to 10 in total). This replaces the skill's default of ≤ 5 files in total.
  Files outside both platform folders (docs, CI, `.ai/`) count towards both.
  Every TrainAlarm change is built on both platforms, so the default limit would
  send even mechanical fixes down the full path.
- All the skill's other trivial criteria still apply unchanged: no new structural
  units, no new dependencies, no new test infrastructure, no design decisions.

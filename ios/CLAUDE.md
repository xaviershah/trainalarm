# TrainAlarm — iOS conventions

Swift, SwiftUI. Project defined via `project.yml` (XcodeGen) — the
`.xcodeproj` is generated, not committed. If you add/remove/move a source
file, XcodeGen picks it up automatically on next `xcodegen generate` since
sources are folder-referenced — no manual project-file editing needed.

## Commands
- Regenerate project: `xcodegen generate` (from `ios/`)
- Build/test: via Xcode, or `xcodebuild -scheme TrainAlarm test` once the
  project is generated (needs a real Mac with Xcode — not this linked
  session's shell).

## Conventions
- One folder per architectural layer (`Models`, `Providers`, `Tracking`,
  `Alarm`, `App`) — matches the stages in ../docs/spec.md §8.
- `TrainDataProvider` is a protocol in `Providers/`; the RTT implementation
  is a separate type conforming to it — call sites depend on the protocol,
  never the concrete RTT type, per the provider-boundary decision in
  docs/spec.md §2.
- Don't add an entitlement key to `TrainAlarm.entitlements` without reading
  it directly off Apple's current AlarmKit documentation first — the
  placeholder there is deliberately empty, not an oversight.

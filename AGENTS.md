# SDK development instructions

## Cross-machine handoff

- Startup reading order, including after switching machines: this file ->
  `DEV_HANDOFF.md` -> `../JMESources/RENDERING_QUALITY_ROADMAP.md`.
  The local handoff contains the Lemur/dual-GUI and coding/scene Agent backlog;
  the engine roadmap contains rendering and large-world surface-card performance
  work. Remind the user of relevant pending items when resuming that area.
  Plans are not implemented features; backlog entries alone do not authorize implementation.

- Read `DEV_HANDOFF.md` before continuing. For engine integration also read
  `../JMESources/AGENTS.md` and `../JMESources/DEV_HANDOFF.md`.
- Keep this file for stable rules. Update `DEV_HANDOFF.md` after meaningful work
  and before a machine switch: completed changes, tests actually run, limitations,
  open issues, next steps and compatible engine/SDK implementation revisions.
- Commit handoff documents with related changes. Documentation-only commits may
  follow the recorded implementation revisions. Verify remote state before
  reporting push success. Never store tokens, private keys or credentials.

## Implementation and validation

- The user owns SDK builds. Do not export/build/package/install/launch the SDK unless explicitly requested. Use targeted compilation or isolated diagnostic programs for verification; do not replace installed SDK modules.

- Preserve unrelated user modifications. Use four-space indentation and braces;
  keep independent statements on separate lines.
- Document changed user-visible behavior and defaults in relevant source comments;
  include Chinese step-by-step usage examples for new editor features.
- Swing component creation and editing belong to EDT. Scene mutation/clone work
  belongs to the owning render thread; preview installation belongs to its own
  render thread. Do not block EDT waiting for rendering or long-running I/O.
- Graph edits must preserve undo/redo and dirty state. Saving must preserve the
  original asset when serialization fails; document any non-atomic fallback.
- Test meaningful editor interactions and persistence. For preview changes also
  run native OpenGL/AWT verification; do not infer visible rendering from compile
  success. Report limitations of automated tests accurately.
- Keep J3O as the common asset extension. Animation detection and skinning are
  distinct; preserve explicit editor preference and legacy default routing.

## Build and portability

- Package future SDK JNI integrations like Bullet: build pinned native
  dependencies for the supported desktop OS/architecture matrix in advance,
  collect the platform libraries and license notices during the SDK build, and
  load the matching bundled file at runtime. No user-side compiler or download
  should be required. Release packaging must detect missing target binaries.
  Record separately which binaries were built, packaged and run.

- Keep `jmesdk` and `JMESources` as sibling directories. Read `readme.md`,
  `build-local-engine-sdk.bat`, `build-and-run-sdk.bat` and `gradle/local-engine.gradle`
  before changing local-engine integration.
- Use the same exported engine for SDK runtime and project libraries. Export the
  engine before building SDK. Configure a suitable local JDK (last validated: 25).
- Adapt machine-specific JDK paths in existing test scripts before running them
  on a new computer. Do not assume the original developer's Windows paths exist.
- Do not commit downloaded native tools, generated engine JARs, extracted test
  assets or local build logs as handoff data. External game projects and models
  need separate transfer; GitHub authentication is configured on each machine.

## Comment style

- Match the surrounding author style. Keep comments concise and focused on purpose, non-obvious reasons and constraints; avoid template-like narration and redundant descriptions.

## Required JNI delivery (user policy, 2026-11-24)

- Ship JNI integrations like Bullet: commit the bridge sources AND all supported
  precompiled DLL/SO/dylib variants, their licenses and a pinned checksum manifest
  under `native-bundles`. CI artifacts or ignored build/release directories alone
  do not deliver the dependency to another machine.
- The supported desktop matrix is Windows/Linux/macOS on x86_64 and ARM64.
  SDK builds stage those tracked binaries and verify completeness and hashes.
  Record cross-compilation separately from native runtime verification.
- Future JNI development must update and push this complete package with the
  matching source changes; do not require users to install a native compiler.

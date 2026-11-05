# Nyx Codex Instructions

These instructions are for Codex runs in this repository.

## Read Order

Always read `AGENTS.md` first.

## Environment

- This repository must run on JDK 25.
- Use `./codex/mvnw.sh ...` for Maven commands so the correct Java environment is loaded automatically.
- If `./codex/mvnw.sh` fails to locate JDK 25, stop and fix the environment before making code changes.
- Prefer the Maven wrapper over any system Maven installation.
- For Codex runs in this repository, default to `danger-full-access` sandbox mode. Maven validation in this project has proven unreliable under the standard sandbox.

## Repo Shape

- This is a multi-module Java/Javalin repository rooted at `nyx-media-streamer`.
- Playback work for this stream primarily lives in:
  - `modules/playback/contracts-playback`
  - `modules/playback/playback-runtime`
  - `modules/playback/playback-http`
  - `modules/transcode/transcode-core`
  - `modules/transcode/transcode-runtime`
  - `modules/transcode/transcode-http`
  - `modules/ffmpeg/ffmpeg-core`
  - `modules/ffmpeg/ffmpeg-runner`
  - `modules/application/app`

## Qloud Compatibility Guardrail

- Treat the Qloud compatibility shim as a strict compatibility contract for an old third-party client we do not control.
- Do not "clean up", rename, modernize, or remove Qloud response fields, handshake behavior, path tolerance, proxy-host handling, or legacy MPEG-TS HLS behavior unless a product decision explicitly deprecates that client.
- Preserve the old-client path covered by `QloudCompatibilityRoutesTest.legacyQloudClientContractBridgesTokensBrowsesFilePathAndStreamsTsThroughProxyHost`: bridged native hello/auth tokens, LAN-facing proxy host/port echoing, file-path `/proc/list` browse tolerance, and `NYX_QLOUD_LEGACY_TS_HLS=true` MPEG-TS HLS playback.
- Keep compatibility fixes inside the Qloud shim where possible. Do not weaken normal Nyx APIs to match legacy Qloud behavior.

## Workflow

- Work one milestone at a time, in the active task plan order.
- Keep diffs scoped to the current milestone.
- Run the milestone validation commands before moving on.
- If validation fails, repair the failure before continuing.
- Preserve compatibility behavior unless the spec explicitly changes it.
- Do not rewrite completed milestones without a concrete reason.

## Validation Expectations

- Prefer targeted module tests while implementing a milestone.
- Run broader validation at phase boundaries and at the end of the workstream.
- Record validation anomalies separately from routine command history, including symptom, evidence, root cause, and repair.
- For this repo, the default broad validation is:
  - `./codex/mvnw.sh test`
- Final validation for a substantial playback-platform run should include:
  - `./codex/mvnw.sh -pl :app -am -DskipTests install`
  - `./codex/mvnw.sh test`
  - `./codex/mvnw.sh verify -Pcoverage`
  - `./codex/mvnw.sh -f modules/application/app/pom.xml org.owasp:dependency-check-maven:check`
  - `./codex/mvnw.sh -f modules/application/app/pom.xml -Pbenchmark test-compile jmh:benchmark`
  - `./codex/mvnw.sh -pl :app -am -DskipTests package`

## Git Hygiene

- Prefer running substantial work from a dedicated Git worktree or a clean branch.
- If the working tree already contains unrelated edits, do not revert them.
- If existing edits overlap with the current milestone, stop and flag the conflict before proceeding.
- Do not run concurrent Maven validation workflows in the same checkout unless they are explicitly serialized behind a shared lock.

## Stop Conditions

Stop only for a real blocker, such as:

- required repository context is missing
- the environment cannot run the required validation commands
- the milestone cannot be completed without a product decision not covered by the active spec

Do not treat a task as complete until final validation and review have passed.

When stopped, record the blocker and the exact next action.

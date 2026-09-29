---
name: release
description: Assess release readiness, prepare release notes and a release PR, or walk the
  maintainer through publishing a prepared sempods-kotlin release to Maven Central. Invoke on "is
  0.2 ready to release?", "prepare the 0.2.0 release", "publish 0.2.0". With no mode it assesses;
  only an explicit publication request publishes.
argument-hint: "[assess | prepare | publish] [version]"
---

# release

The procedure is [`docs/agents/release.md`](../../../docs/agents/release.md). **Read it and follow
it** for the mode and version in `$ARGUMENTS`; with no mode, assess. This file holds no copy of its
steps.

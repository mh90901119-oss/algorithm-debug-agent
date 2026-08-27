# CodePathTracer pinned artifact

The Agent keeps CodePathTracer as a third-party library instead of copying its source into an Agent
module. Version `0.1.0-SNAPSHOT` is stored in `third-party/maven-repository` so a source checkout can
build when the configured Maven mirror does not provide this artifact.

- Upstream coordinate: `io.github.takahirom.codepathtracer:code-path-tracer:0.1.0-SNAPSHOT`.
- License: Apache License 2.0; see `LICENSE`.
- The JAR, sources JAR, and POM must be replaced together when upgrading.
- After an upgrade, run the CodePath Launcher contract tests and the real CodePath smoke case.

The repository-local Maven source has snapshots enabled with update policy `never`. Maven continues
to resolve mainstream dependencies and plugins from the configured Maven mirror.

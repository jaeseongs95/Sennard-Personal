[한국어](testing-and-ci.ko.md)

# Roles of CI and the Test Servers

## Principle

When CI fails, the cause must be a code defect. Tests that fail because a runner is busy are not kept in CI. Measurements that use real hosts and models are made on the Linux and Windows test servers.

## Test Composition

All automated tests run with Gradle and JUnit.

- `./gradlew test`: unit and integration tests for each module. This includes the repository contract check, the message catalog check, the domain-neutral wording check, the skill check, and the repository tests that do not need the business database. The tests tagged `business-db` are left out of `test` and run separately as `businessDbTest` against a database you name (see the "Running the Tests" section of the [Contributing Guide](../../CONTRIBUTING.md)). The `test` task of `sennard-personal` includes the server tests that run on a real Tomcat and JVM and the process tests that install and run the release jar the build made (`releaseJar`, and its extracted form). `./gradlew :sennard-personal:jarRun` runs that jar as a user would, from a folder with a non-ASCII name; it is not part of `test`. The release was verified on one Windows PC only (see Where Tests Run).

The test JVM starts without the host variables of the session that ran Gradle, with its home and temporary folders under `build/isolated/<task>/` of the module (`isolateFromHost` in the root `java/build.gradle.kts`). Therefore a development PC uses `./gradlew` alone, without a shell wrapper, and tests of different working copies can run at the same time. The test servers are used only for model measurements. The start conditions and the selection of JDK 25 are in the "Running the Tests" section of the [Contributing Guide](../../CONTRIBUTING.md).

Data contract vectors and fixtures are kept as files in `tests/<skill>/fixtures` and `skills/cs-engineering/assets/examples`, and JUnit parameterized tests read them.

## Where Tests Run

| Location | Command | Scope |
|---|---|---|
| GitHub Actions (ubuntu and windows, JDK 25 Temurin) | `./gradlew --no-daemon --console=plain test --fail-fast` (the business database tests are not in `test`; they run separately as `businessDbTest`. Without `--continue`: a failed test task stops at the first failure and no new task is started, but test tasks of other modules that are already running in parallel run to completion. When the job of one OS fails, the job of the other OS is cancelled) | All automated tests of `test` (not `businessDbTest`) |
| Linux and Windows test servers | Real-host tests | The actual behavior of the packages installed in Claude, Codex, and agy |

A release candidate must pass both the full CI tests and the real-host tests of Claude, Codex, and agy. Measurement tests are not placed in CI.

Verification range of 6.0.0: the release jar, its installation, and the host connection check were run on one Windows PC. The code is the same on every operating system, because the JVM absorbs the differences and no code chooses a branch by the name of the operating system; but the branches that depend on the file-system permission model (POSIX permissions) have not been run on Linux or macOS. This is a stated limit of the release, not a claim that those systems were tested.

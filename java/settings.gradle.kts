pluginManagement {
    // The build logic of this build (the heap ledger and the plugin) is an included build that provides a plugin, not buildSrc: buildSrc is built on every run but its tests run
    // on no task of this build (D-0230). Its tests are compiled and run by sennard-repo-tools, so that they take part in the root build's limit on the tasks that reserve heap (D-0270):
    // https://docs.gradle.org/current/userguide/composite_builds.html#included_plugin_builds
    includeBuild("build-logic")

    // Spotless (Apache-2.0), used only while the build runs, for the licenseHeader step (the SPDX header of the source files, AGS3-D-0266). Its version is stated here, once.
    // 8.10.4 (2026-10-08) is the newest release older than two weeks; its release notes (8.10.0: JVM 25 handled, 8.10.3: no call that Gradle 9 removed):
    // https://github.com/diffplug/spotless/blob/main/plugin-gradle/CHANGES.md
    plugins {
        id("com.diffplug.spotless") version "8.10.4"
        // Gradle-License-Report (Apache-2.0: https://github.com/jk1/Gradle-License-Report/blob/master/LICENSE.txt), used only while the build runs: it reads the license of every dependency of the
        // shipped class path from the POMs (parent POMs included), normalizes the names and checks them against java/config/license/allowed-licenses.json (sennard-personal: checkLicense).
        // 3.1.5 (2026-11-01) is the newest release older than two weeks (https://plugins.gradle.org/plugin/com.github.jk1.dependency-license-report). Its tasks declare themselves
        // incompatible with the configuration cache (notCompatibleWithConfigurationCache in ReportTask and CheckLicenseTask), so a build that runs them runs without it; other builds are unaffected.
        id("com.github.jk1.dependency-license-report") version "3.1.5"
    }
}

plugins {
    // The toolchain asks for JDK 25 (build.gradle.kts). A machine that has none gets it downloaded, so the build needs no
    // JDK set up by hand: https://docs.gradle.org/current/userguide/toolchains.html (Toolchain Download Repositories).
    // A machine that has one (JAVA_HOME, or org.gradle.java.installations.paths in its own gradle.properties) never downloads.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sennard-java"

include("sennard-domain", "sennard-app", "sennard-protocol", "sennard-api", "sennard-hook-client", "sennard-core", "sennard-migration", "sennard-store-pg", "sennard-server", "sennard-personal", "sennard-settings", "sennard-repo-tools", "sennard-arch-checks")

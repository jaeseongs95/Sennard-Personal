// The repository's own tooling, run as Gradle tasks (see the bottom of this file): generated plugin trees, release metadata, the public
// export, and the JUnit checks of the repository's contracts. It is not part of the product and nothing depends on it.
plugins {
    java
}

dependencies {
    // Hook definition files come from the adapters that answer the hooks.
    implementation(project(":sennard-server"))
    implementation("tools.jackson.core:jackson-databind")
    // Git without the git program. The version is the root build's (jgitVersion).
    implementation("org.eclipse.jgit:org.eclipse.jgit")
    // Parses each skill's agents/openai.yaml in the contract test; the version comes from the Boot BOM.
    testImplementation("org.yaml:snakeyaml")
    // HeapLedgerBuildTest runs a build of its own through Gradle TestKit, which brings the TestKit classes and the Tooling API client (not the Gradle API):
    // https://docs.gradle.org/current/userguide/test_kit.html#sub:test-kit-setup
    testImplementation(gradleTestKit())
}

// The tests of the build logic (java/build-logic: the heap ledger, HeapLedgerTest) run here, as part of this module's `test`, and so as a Test task of the root build (D-0230, D-0270).
// A Test task of the included build would be outside the root build's limit on the tasks that reserve heap (SennardBuildLogicPlugin, HeapSlot): a shared build service limits the tasks
// that use it per build, and the included build's Test waits in its first action for the reservation of the root build's Test while it holds a worker place. The folders are
// sources of the included build, which compiles them too; the classes that need the Gradle API (the plugin, the build service and the three notices tasks; ThirdPartyNotices itself is JDK only and is tested here) are left out, as the tests do not use them and the
// API (Groovy, SLF4J, Ant) is not put on this module's class path: HeapLedger and HeapBudget use the JDK only.
sourceSets.test {
    java {
        srcDir("../build-logic/src/test/java")
        srcDir("../build-logic/src/main/java")
        exclude("**/SennardBuildLogicPlugin.java", "**/HeapReservationService.java", "**/*ThirdPartyNoticesTask.java")
    }
}

val repoRoot = rootDir.parentFile.absolutePath

// Repo.trackedFiles() reads the index and untracked files. Recompute the names each build: a Git index-only change is not a file-tree content change.
val repositoryNamesFile = layout.buildDirectory.file("repository-inputs/tracked-files.json")
val repositoryNames = tasks.register<JavaExec>("repositoryInputNames") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.github.jaeseongs95.sennard.repotools.RepositoryInputNames"
    maxHeapSize = "256m"
    args(repoRoot, repositoryNamesFile.get().asFile.absolutePath)
    outputs.file(repositoryNamesFile)
    outputs.upToDateWhen { false }
}

tasks.named<Test>("test") {
    systemProperty("sennard.repo.root", repoRoot)
    // Contract checks scan repository-wide text; generated-file and lock checks also read assets.
    inputs.files(fileTree(repoRoot) {
        // Gradle output roots, not every path segment called "build": build-logic's Java package has that name too.
        exclude(".git", ".git/**", "**/.git/**", "**/.gradle/**", "build/**", "java/build/**", "java/*/build/**", "**/__pycache__/**")
    }).withPropertyName("repositoryContracts").withPathSensitivity(PathSensitivity.RELATIVE)
    dependsOn(repositoryNames)
    inputs.file(repositoryNamesFile).withPropertyName("repositoryInputNames").withPathSensitivity(PathSensitivity.NONE)
}

/** A tool run from the repository root. Its arguments come from {@code -Ptool="…"}. */
fun repoTool(name: String, summary: String, command: String, main: String = "io.github.jaeseongs95.sennard.repotools.RepoTools") = tasks.register<JavaExec>(name) {
    group = "repository"
    description = summary
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = main
    workingDir = rootDir.parentFile
    args(command.split(" ").filter { it.isNotEmpty() })
    (project.findProperty("tool") as String?)?.let { args(it.split(" ")) }
}

repoTool("checkReleaseMetadata", "Fails when a manifest or README differs from release/version.json.", "release-metadata")
repoTool("syncReleaseMetadata", "Rewrites every manifest and README from release/version.json.", "release-metadata --write")
repoTool("hostHookFiles", "Writes the hook definition files the plugin packages ship from the adapters' output.", "host-hook-files --write")
repoTool("claudePlugin", "Regenerates claude-plugin/ from skills/, contracts/ and claude-overlay/.", "claude-plugin --write")
repoTool("checkClaudePlugin", "Fails when claude-plugin/ differs from a fresh render.", "claude-plugin")
repoTool("updateSourceLock", "Rewrites each integrated checksum in skills/source-lock.json from the current skills/.", "source-lock --write")
repoTool("updateClassificationDigests", "Rewrites the digest of each classification span from the file it cites; the spans themselves are never moved.", "classification-digests --write")
repoTool("updateCsEngineeringLock", "Rewrites the digests of the cs-engineering knowledge pack and of its frozen example from the files they name.", "cs-engineering-lock --write")
repoTool("checkCsEngineeringLock", "Fails when a digest of the cs-engineering knowledge pack or of its frozen example differs from the file it names.", "cs-engineering-lock")
repoTool("antigravityPackage", "Builds the Antigravity install copy from a ref: -Ptool=\"--ref <ref> --out <file.zip>\".", "antigravity-package")
// What the CI workflows do besides building and testing (the run steps of a job without write permission call only ./gradlew, so these are Gradle tasks and not shell steps).
repoTool("checkWhitespace", "Fails on whitespace problems in the lines HEAD adds (git diff --check): -Ptool=\"--base <sha>\" checks base...HEAD, no base checks HEAD against its parent.", "check-whitespace")
repoTool("checkReleaseArchive", "Checks the name of the release jar against the tag and the version and writes its checksum: -Ptool=\"--tag <tag> --dir <folder>\".", "release-archive")
repoTool("checkReleaseChecksums", "Checks the release jar and its checksum file in one folder against each other: -Ptool=\"--dir <folder>\".", "release-checksums")
repoTool("pruneMessages", "Removes catalog keys under the given prefixes that no product source refers to, from every locale and lock: -Ptool=\"<prefix> <prefix> ...\".", "i18n-prune")
repoTool("acceptTranslation", "Records the current source digests for a locale's reviewed translations: -Ptool=\"<locale>\".", "i18n-accept")
// The export tool is withheld from the public snapshot (by the export list of the source repository), so the task exists only where its source does.
if (file("src/main/java/io/github/jaeseongs95/sennard/repotools/PublicExport.java").exists()) {
    repoTool("publicExport", "The public snapshot: -Ptool=\"check --ref <ref>\", \"extract --ref <ref> --out <dir>\" or \"commit --ref <ref> --public-clone <dir> --version X.Y.Z [--public-base origin/main]\".", "", "io.github.jaeseongs95.sennard.repotools.PublicExport")
    // The export check of the HEAD commit runs in `check` (and so in `build`): a path that the snapshot would leak, and an exported file that names a withheld file (a design document the
    // public repository does not have), fail the build here and not only when someone runs publicExport by hand (D-0118). It reads the committed tree, as the export does.
    val publicExportCheck = tasks.register<JavaExec>("checkPublicExport") {
        group = "verification"
        description = "Fails when the export of HEAD leaks a private path or an exported file refers to a withheld file."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = "io.github.jaeseongs95.sennard.repotools.PublicExport"
        workingDir = rootDir.parentFile
        args("check", "--ref", "HEAD")
    }
    tasks.named("check") { dependsOn(publicExportCheck) }
}

if (file("src/main/java/io/github/jaeseongs95/sennard/repotools/EditionPackage.java").exists()) {
    dependencies { implementation(project(":sennard-protocol")) }
    tasks.register<JavaExec>("packageEditionSources") {
        group = "repository"
        description = "Packages verified private main sources. Set -Pedition=personal|enterprise|hosted and -PpackageOutput=<file.zip>. Optional -PpackageSource=<checkout>."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = "io.github.jaeseongs95.sennard.repotools.EditionPackage"
        workingDir = rootDir.parentFile
        args(findProperty("edition") ?: "", findProperty("packageSource") ?: repoRoot, findProperty("packageOutput") ?: "")
    }
}

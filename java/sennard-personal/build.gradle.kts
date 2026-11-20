import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer

// P1 assembly: the only place that wires the business database connection and the local file sealer.
plugins {
    `java-library`
    id("org.springframework.boot") version "4.1.2"
    // Its version is in java/settings.gradle.kts (pluginManagement). The license checks of the shipped class path are at the end of this file.
    id("com.github.jk1.dependency-license-report")
}

dependencies {
    api(project(":sennard-api"))
    api(project(":sennard-core"))
    api(project(":sennard-store-pg"))
    // Sennard's own settings database (H2): the passkey registry, the admin records made before the link and the link settings. Only this assembly has the H2 implementation.
    implementation(project(":sennard-settings"))
    implementation(project(":sennard-server"))
    // The hook client and the MCP relay: the contract the server shares with them, and the programs the installed host definitions run (their jar is in the installed version's lib folder, with the other modules').
    implementation(project(":sennard-hook-client"))
    implementation("org.yaml:snakeyaml")
    // BusinessDb: the business database the server tests and the installation tests run on (businessDbTest; the person running them names it).
    testImplementation(testFixtures(project(":sennard-store-pg")))
    // BOM-managed Spring TestContext for the opt-in registered-key test; the existing JUnit runner is unchanged.
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-test")
    // Test-only NIO provider: portable real aliases without native link-creation privileges.
    testImplementation("com.google.jimfs:jimfs:1.3.2")
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    // Read here, at configuration: the filter runs at execution, where Task.project is deprecated (an error in Gradle 10).
    val projectVersion = project.version.toString()
    filesMatching("application.yaml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("project.version" to projectVersion))
    }
}

// The one thing Sennard is shipped as: sennard-<version>.jar, a Spring Boot jar the user runs with their own JDK (java -jar sennard-<version>.jar install). There is no jlink runtime and no
// jpackage launcher: the JVM absorbs the difference between operating systems, so the same file is the release on every OS and CPU.
// What the jar holds besides the program: the skill folders and the contract schemas under sennard-content/ (a host reads SKILL.md and the server reads registry.json and the provider
// schemas from folders; sennard install copies them out of the jar), and the license (AGPL-3.0-or-later and its section 7 terms) and the notices of the third-party code under META-INF/.
// The jar is reproducible (no file times, a fixed order of entries), so that the jar CI builds and the one built on the maintainer's computer have the same sha256 and a release that differs stops.
val thirdPartyNotices = rootDir.parentFile.resolve("THIRD_PARTY_NOTICES.md")
val licenseFiles = files(thirdPartyNotices, rootDir.parentFile.resolve("LICENSE"), rootDir.parentFile.resolve("ADDITIONAL-TERMS.md"), rootDir.parentFile.resolve("ADDITIONAL-TERMS.ko.md"))

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "sennard-${project.version}.jar"
    // The user's command is the entry point; the server's main class is started from the extracted application jar (Installer), never from this jar.
    mainClass = "io.github.jaeseongs95.sennard.personal.cli.SennardCli"
    manifest {
        attributes("Implementation-Version" to project.version)
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootDir.parentFile.resolve("skills")) {
        exclude("**/__pycache__/**")
        into("sennard-content/skills")
    }
    from(rootDir.parentFile.resolve("contracts")) { into("sennard-content/contracts") }
    from(licenseFiles) { into("META-INF") }
}

val personalJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFile }

// The product modules whose compiled classes the boundary test reads: this one, the server, the core and the protocol.
val productClasses = listOf(":sennard-protocol", ":sennard-core", ":sennard-server")

// Fixtures, schemas and source files read through sennard.repo.root by integration and workflow tests.
val repositoryTestInputs = fileTree(rootDir.parentFile) {
    include("skills/**", "contracts/**", "claude-plugin/contracts/**", "tests/**", "conformance/**",
        "java/sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/skills/**",
        "java/sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/workflow/**",
        // HostInputFieldsTest reads the hook client's lists of each host's input fields from the module's sources.
        "java/sennard-hook-client/src/main/resources/sennard-hook-input/**")
    exclude("build/**", "java/build/**", "java/*/build/**", "**/.git/**", "**/.gradle/**", "**/__pycache__/**")
}

// `test` and `businessDbTest` (the tests tagged business-db, which need the database named by -Psennard.test.businessDb.*; java/build.gradle.kts) read the same inputs.
tasks.withType<Test>().matching { it.name == "test" || it.name == "businessDbTest" }.configureEach {
    // The baseline measurement (PerfBaselineMeasurement) is the perfBaseline task's; excluded by tag it is not run here and shows up as no skipped test. The tests that
    // need the database are businessDbTest's: `test` leaves them out by tag, and businessDbTest selects only them (root build, which sets that).
    if (name == "test") {
        useJUnitPlatform { excludeTags("perf-baseline", "release-jar", "jev-live", rootProject.extra["businessDbTag"] as String) }
    }
    productClasses.forEach { dependsOn("$it:classes") }
    val directories = productClasses.map { project(it).layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath } +
        layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
    systemProperty("sennard.product.classes", directories.joinToString(File.pathSeparator))
    // EncodingBoundaryTest reads every module's compiled classes, the two outside the product's boundary list included.
    val encodingModules = productClasses + listOf(":sennard-api", ":sennard-migration", ":sennard-store-pg", ":sennard-settings")
    encodingModules.forEach { dependsOn("$it:classes") }
    systemProperty("sennard.encoding.classes", (encodingModules.map { project(it).layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath } +
        layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath).joinToString(File.pathSeparator))
    // ServerProcessTest runs the built jar (its server main class, through Spring Boot's PropertiesLauncher): a real JVM, a real Tomcat.
    dependsOn("bootJar")
    inputs.file(personalJar)
    systemProperty("sennard.personal.jar", personalJar.get().asFile.absolutePath)
    // Skill judgment cases read the repository's own fixtures; a missing fixture fails the case.
    systemProperty("sennard.repo.root", rootDir.parentFile.absolutePath)
    inputs.files(repositoryTestInputs).withPropertyName("repositoryTestInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    // The golden tests (serverit/golden) compare by default and rewrite their files only when the build is run with -Dsennard.golden.update=true.
    systemProperty("sennard.golden.update", providers.systemProperty("sennard.golden.update").getOrElse("false"))
}

// Explicit opt-in comparison only; not a dependency of test/check/build. Existing registration is an input, not a separate test target.
// Settings H2 and the business store are opened read-only, without migration. At most 36 paid calls.
tasks.register<Test>("jevRegisteredKeyTest") {
    description = "Compares frozen r01 and the current JEV candidate using the registered key; explicit bounded paid calls."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("jev-live-comparison") }
    maxParallelForks = 1
    systemProperty("sennard.test.jev.repo", rootDir.parentFile.absolutePath)
    val stateDirectory = providers.gradleProperty("sennard.test.jev.stateDir")
    val costLimit = providers.gradleProperty("sennard.test.jev.maxEstimatedUsd")
    stateDirectory.orNull?.let { systemProperty("sennard.test.jev.state-dir", it) }
    costLimit.orNull?.let { systemProperty("sennard.test.jev.max-estimated-usd", it) }
    inputs.files(repositoryTestInputs)
    outputs.upToDateWhen { false } // Never reuse a previous registration or model observation as a new execution.
    doFirst {
        require(stateDirectory.isPresent) { "Explicit -Psennard.test.jev.stateDir is required; no key environment variable is used." }
        require(costLimit.isPresent && costLimit.get().toDouble().let { it.isFinite() && it > 0 }) {
            "Explicit positive -Psennard.test.jev.maxEstimatedUsd is required before paid calls."
        }
    }
}

// The actions of the tasks below hold local values and this service, not the script's own properties and not Project.delete: an action that names them holds the script or the project,
// which the configuration cache cannot store (https://docs.gradle.org/current/userguide/configuration_cache_requirements.html). FileSystemOperations deletes as Project.delete did.
interface BuildServices {
    @get:javax.inject.Inject
    val files: FileSystemOperations
}
val buildServices = objects.newInstance<BuildServices>()
val extractedDir = layout.buildDirectory.dir("extracted")
val jdkHome = extensions.getByType<JavaToolchainService>().launcherFor(extensions.getByType<JavaPluginExtension>().toolchain)
    .map { it.metadata.installationPath.asFile.absolutePath }

// The cost of one request at the hook and workflow entry points (6.0.0 plan, stage 2), measured with the server's own JVM options and a JDK Flight
// Recorder recording, and the session start of a real installation five times (HookStartsServerProcessTest, in the log); the 6.0.0 redesign is judged against the same run. Results go to -Psennard.perf.out (default build/perf); -Psennard.perf.count and
// -Psennard.perf.warmup change the request counts (200 each by default). Run: ./gradlew :sennard-personal:perfBaseline -Psennard.perf.out=<folder> with the database of businessDbTest named (-Psennard.test.businessDb.*): the measured server runs on it
tasks.register<Test>("perfBaseline") {
    description = "Measures the latency, CPU, allocation and hot methods of the hook and workflow entry points (JFR) and the session start on a real installation; not part of test."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("perf-baseline") }
    // The options of the installed server, so the measured JVM is configured as the installed one: copy them from JvmOptions.server (the JVM preset in the installed version's sennard-jvm.yaml),
    // e.g. -Psennard.perf.jvm-args="-XX:+UseG1GC -Xmx512m". The heap is one of them
    // and is not stated again here; without the property the JVM's own defaults measure.
    providers.gradleProperty("sennard.perf.jvm-args").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotEmpty)) }
    enableAssertions = false // Gradle turns -ea on for tests; the shipped server runs without it
    systemProperty("sennard.repo.root", rootDir.parentFile.absolutePath)
    inputs.files(repositoryTestInputs).withPropertyName("repositoryTestInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("sennard.perf.out", providers.gradleProperty("sennard.perf.out").getOrElse(layout.buildDirectory.dir("perf").get().asFile.absolutePath))
    providers.gradleProperty("sennard.perf.count").orNull?.let { systemProperty("sennard.perf.count", it) }
    providers.gradleProperty("sennard.perf.warmup").orNull?.let { systemProperty("sennard.perf.warmup", it) }
    maxParallelForks = 1 // a measurement: one JVM, so that no other test class competes for the CPU
    outputs.upToDateWhen { false }
    testLogging { events("failed", "passed") }
}

// The Boot jar extracted the way an installation holds it (the application jar and lib/ beside it, Spring Boot's -Djarmode=tools extract): the layout the JDK's AOT cache needs, which a
// jar of nested jars cannot have. The AOT cache of the hook client below is made from it (the installer makes the installed copy's own, CommandAotCache).
val extractBootJar = tasks.register<Exec>("extractBootJar") {
    description = "Extracts the Boot jar: the application jar plus lib/ (the layout AOT caching needs)."
    dependsOn("bootJar")
    inputs.file(personalJar)
    outputs.dir(extractedDir)
    val inputDirectory = extractedDir.get().asFile
    val fileSystem = buildServices.files
    doFirst { fileSystem.delete { delete(inputDirectory) } }
    executable = jdkHome.get() + "/bin/java"
    args("-Djarmode=tools", "-jar", personalJar.get().asFile.absolutePath, "extract", "--destination", extractedDir.get().asFile.absolutePath)
}

// The release: the jar and its sha256 (<hex>  <name>, what sha256sum --check reads) in build/release. The Server package workflow builds this on one runner and checks the name against the
// tag (:sennard-repo-tools:checkReleaseArchive); the maintainer's computer builds it too, and a release is published only when the two sha256 are the same.
// The task is a Sync of the Boot jar into build/release (release-jar.gradle.kts), so a build/release left by an earlier build is replaced, not an error.
apply(from = "release-jar.gradle.kts")
val releaseJarFile = layout.buildDirectory.file("release/sennard-${project.version}.jar")
val releaseJar = tasks.named("releaseJar")

// Licenses of the third-party code in the release jar (plan decision 25: AGPL-3.0-or-later, ADDITIONAL-TERMS.md). The jar's class path is this module's runtimeClasspath (the Boot jar
// nested in BOOT-INF/lib, the project modules' jars among them: sennard-hook-client has no dependency of its own); the user's JDK is not a Gradle dependency and is not shipped.
// The Gradle-License-Report plugin (Apache-2.0, version in java/settings.gradle.kts) reads each dependency's license from its POM, the parent POMs included, and normalizes the names with its own
// bundle plus java/config/license/license-normalizer-bundle.json. `checkLicense` fails the build, and lists every dependency with the license strings it found, when none of a dependency's
// licenses is in java/config/license/allowed-licenses.json (a dependency with no license is not allowed either). The same report feeds THIRD_PARTY_NOTICES.md. The plugin's tasks are not
// configuration cache compatible (they say so themselves), so a build that runs them runs without that cache.
licenseReport {
    projects = arrayOf(project)
    configurations = arrayOf("runtimeClasspath")
    excludeOwnGroup = true // the modules of this build are the product, not third-party code
    excludeBoms = true
    excludes = arrayOf("org.springframework.boot:spring-boot-dependencies") // a BOM whose name does not end in -bom
    renderers = arrayOf<ReportRenderer>() // the report file the notices read is written by the check's preparation (checkLicensePreparation), no HTML report is wanted
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer(rootDir.resolve("config/license/license-normalizer-bundle.json").absolutePath, true))
    allowedLicensesFile = rootDir.resolve("config/license/allowed-licenses.json")
}

// THIRD_PARTY_NOTICES.md at the repository root is generated from that report (generateThirdPartyNotices) and checked to be current (checkThirdPartyNotices, part of `check`).
val licenseReportJson = layout.buildDirectory.file("reports/dependency-license/project-licenses-for-check-license-task.json")
tasks.register<io.github.jaeseongs95.sennard.build.GenerateThirdPartyNoticesTask>("generateThirdPartyNotices") {
    description = "Rewrites THIRD_PARTY_NOTICES.md from the licenses of the dependencies in the release archive."
    group = "licenses"
    dependsOn("checkLicensePreparation", "generateLicenseReport")
    licenseData.set(licenseReportJson)
    noticesFile.set(thirdPartyNotices)
}
val checkThirdPartyNotices = tasks.register<io.github.jaeseongs95.sennard.build.CheckThirdPartyNoticesTask>("checkThirdPartyNotices") {
    description = "Fails when THIRD_PARTY_NOTICES.md is not current with the licenses of the dependencies in the release archive."
    group = "verification"
    dependsOn("checkLicensePreparation", "generateLicenseReport")
    licenseData.set(licenseReportJson)
    noticesFile.set(thirdPartyNotices)
}
tasks.named("check") { dependsOn("checkLicense", checkThirdPartyNotices) }

// The AOT cache of the hook client on the extracted jar (JDK 25 "Ahead-of-Time Cache": training run with -XX:AOTMode=record, then -XX:AOTMode=create; the application itself does
// not run in the second step). A host's command hook runs the user's own java and the hook client's jar (sennard-hook-client, in the extracted lib folder) as the whole class path
// (HookClientCommand); the training runs the same words with the same JVM options as the installed hook (JvmOptions.hookClient: the small serial collector and heap, no JVM log),
// written again here because the build script cannot call the product's code. The result is in build/aot and not in the release: the installer makes the installed copy's cache itself
// (CommandAotCache; an installed copy has another path, and a cache whose JARs changed is not used), so the release jar carries none. The training is `hook gemini BeforeModel` against
// a server file that does not exist, with the Gemini input of src/main/resources/aot; the client then ends with exit code 1 (no server), which is what the training expects. `--warm`
// makes the same run read the Antigravity input of src/main/resources/aot first (a JVM records one run and Antigravity has its own fields), as CommandAotCache does. The client's jar
// is found in the extracted lib folder by its name when the task runs.
val commandAotDir = layout.buildDirectory.dir("aot")
val commandAotInput = layout.projectDirectory.file("src/main/resources/aot/gemini-before-model.json")
val commandAotWarmInput = layout.projectDirectory.file("src/main/resources/aot/antigravity-pre-tool-use.json")
val commandAotNoServerFile = commandAotDir.map { it.file("sennard-aot-no-server.properties") }
val commandAotArguments = listOf("hook", "gemini", "BeforeModel", "--server-file=" + commandAotNoServerFile.get().asFile.absolutePath, "--on-failure=fail", "--warm=agy=" + commandAotWarmInput.asFile.absolutePath)
val hookClientMainClass = "io.github.jaeseongs95.sennard.hookclient.HookClientMain"
val hookClientJvmOptions = listOf("-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-Xlog:disable")

val commandAotRecord = tasks.register<Exec>("commandAotRecord") {
    description = "Records the hook client's classes with the JDK's java (the first step of its AOT cache)."
    group = "distribution"
    dependsOn(extractBootJar)
    inputs.dir(extractedDir)
    inputs.file(commandAotInput)
    inputs.file(commandAotWarmInput)
    val configuration = commandAotDir.map { it.file("sennard-hook.aotconf") }
    outputs.file(configuration)
    isIgnoreExitValue = true // the client ends with exit code 1 when no server answers, which is the training run
    val aotDirectory = commandAotDir.get().asFile
    val configurationFile = configuration.get().asFile
    val trainingInput = commandAotInput.asFile
    val extracted = extractedDir.get().asFile
    val java = jdkHome.get() + "/bin/java"
    val jvmOptions = hookClientJvmOptions
    val mainClass = hookClientMainClass
    val arguments = commandAotArguments
    // `this` of an action is the task that runs it (as Exec here): naming the task or the script's properties from outside would put them into the action, and the
    // configuration cache cannot store the script.
    doFirst {
        aotDirectory.mkdirs()
        configurationFile.delete()
        val jar = extracted.resolve("lib").listFiles { file -> file.name.startsWith("sennard-hook-client-") && file.name.endsWith(".jar") }!!.single()
        val exec = this as Exec
        exec.standardInput = trainingInput.inputStream()
        exec.commandLine(listOf(java) + jvmOptions + listOf("-XX:AOTMode=record", "-XX:AOTConfiguration=" + configurationFile.absolutePath, "-cp", jar.absolutePath, mainClass) + arguments)
    }
    doLast {
        val exit = (this as Exec).executionResult.get().exitValue
        if (exit != 0 && exit != 1) {
            throw GradleException("The AOT training run of the hook client ended with exit code $exit (0 or 1 expected).")
        }
        if (!configurationFile.isFile) {
            throw GradleException("The AOT training run wrote no configuration: $configurationFile")
        }
    }
}

val commandAotCache = tasks.register<Exec>("commandAotCache") {
    description = "Creates the hook client's AOT cache (build/aot/sennard-hook.aot) from the recorded training run, for the extracted jar's class path."
    group = "distribution"
    dependsOn(commandAotRecord)
    val configuration = commandAotDir.map { it.file("sennard-hook.aotconf") }
    val cache = commandAotDir.map { it.file("sennard-hook.aot") }
    inputs.dir(extractedDir)
    inputs.file(commandAotInput)
    inputs.file(commandAotWarmInput)
    outputs.file(cache)
    val configurationFile = configuration.get().asFile
    val cacheFile = cache.get().asFile
    val extracted = extractedDir.get().asFile
    val java = jdkHome.get() + "/bin/java"
    val jvmOptions = hookClientJvmOptions
    val mainClass = hookClientMainClass
    val arguments = commandAotArguments
    doFirst {
        val jar = extracted.resolve("lib").listFiles { file -> file.name.startsWith("sennard-hook-client-") && file.name.endsWith(".jar") }!!.single()
        (this as Exec).commandLine(listOf(java) + jvmOptions + listOf("-XX:AOTMode=create", "-XX:AOTConfiguration=" + configurationFile.absolutePath,
            "-XX:AOTCache=" + cacheFile.absolutePath, "-cp", jar.absolutePath, mainClass) + arguments)
    }
}

// The process cases and the perfBaseline start measurement install the release jar (ReleaseJarInstallFlowTest, HookStartsServerProcessTest), so they need the same jar,
// extracted folder, AOT cache and version as `test`'s cases; all three tasks get them by the one block, as at the inputs above.
tasks.withType<Test>().matching { it.name == "test" || it.name == "businessDbTest" || it.name == "perfBaseline" }.configureEach {
    dependsOn(commandAotCache)
    inputs.file(commandAotDir.map { it.file("sennard-hook.aot") })
    systemProperty("sennard.command.aot.cache", commandAotDir.get().file("sennard-hook.aot").asFile.absolutePath)
    dependsOn(releaseJar, extractBootJar)
    inputs.file(releaseJarFile)
    systemProperty("sennard.release.jar", releaseJarFile.get().asFile.absolutePath)
    inputs.dir(extractedDir)
    systemProperty("sennard.extracted.dir", extractedDir.get().asFile.absolutePath)
    systemProperty("sennard.project.version", project.version.toString())
}

// The release jar run as a user would run it (ReleaseJarRunTest, tag release-jar): copied into a folder with a non-ASCII name and started there by its file name, `java -jar
// sennard-<version>.jar <command>`, with install, status, start, stop, upgrade and uninstall on a state folder of the same kind. The Server package workflow's jar is the one the
// maintainer's computer builds again for the sha256 comparison; this task runs the jar of this build. It is not part of `test`. The test makes the folder itself, from letters
// the JVM's native.encoding can write (a path is passed to the child as the folder it runs in and as environment variables, not as a command-line argument, which a Windows java
// launcher reads in that encoding).
tasks.register<Test>("jarRun") {
    description = "Runs the release jar as a user would: from a non-ASCII folder by its file name, install, status, start, stop, upgrade and uninstall."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("release-jar") }
    productClasses.forEach { dependsOn("$it:classes") }
    dependsOn(releaseJar)
    inputs.file(releaseJarFile)
    systemProperty("sennard.release.jar", releaseJarFile.get().asFile.absolutePath)
    systemProperty("sennard.project.version", project.version.toString())
    maxParallelForks = 1 // each case starts a server on the state folder of its own, one at a time
    outputs.upToDateWhen { false }
    testLogging { events("failed", "passed") }
}

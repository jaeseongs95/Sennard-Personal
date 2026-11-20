plugins {
    // The included build build-logic (java/build-logic): SennardBuildLogicPlugin wires the opt-in heap ledger (SennardBuildLogicPlugin, with HeapLedger and HeapReservationService), and its tests run in sennard-repo-tools (D-0230, D-0270).
    id("io.github.jaeseongs95.sennard.build-logic")
    // Its version is in java/settings.gradle.kts (pluginManagement). It adds spotlessCheck to `check` and spotlessApply, which writes the header into the files that lack it.
    id("com.diffplug.spotless")
}

// The license header of every source file (AGS3-D-0266, ADDITIONAL-TERMS.md): only the licenseHeader step, no formatter, so no code is reshaped.
// The root project has no Java plugin, so the targets are file sets: the Java sources of every module (main, test, testFixtures) and of build-logic, and the SQL scripts a module ships.
// Left out: everything under src/test/resources (golden files and fixtures whose content a test compares). Build output (<module>/build) is not under */src, so it needs no exclusion; an exclusion
// of "**/build/**" (which this had) also dropped every source file whose package folder is named build, which is all of build-logic (io/github/jaeseongs95/sennard/build): it went without a header and
// spotlessCheck passed. build-logic is an included build, but the root project's file set reaches its folder like any other folder of java/, so it is checked here and not by a second Spotless
// of its own (Gradle: an included build is a build of its own, and a plugin of this build applies to this build's projects only; the files are named by path, not by project).
// The generated claude-plugin/ is outside java/.
// YAML, properties, HTML, Markdown and JSON get no header.
// The targets are file trees rooted at each module's src folder, and no target or targetExclude is a pattern over java/: a pattern is a tree of the whole project folder, which Gradle walks (and
// the configuration cache hashes, through the line-ending policy that holds the file set) down into <module>/build, where a running test JVM keeps files open (build/isolated/test/tmp/hsperfdata_*).
val moduleSources = projectDir.listFiles { folder -> folder.resolve("src").isDirectory }!!.sortedBy { it.name }.map { it.resolve("src") }

spotless {
    encoding("UTF-8")
    format("javaSources") {
        target(files(moduleSources.map { source -> fileTree(source) { include("**/*.java"); exclude("test/resources/**") } }))
        licenseHeader(
            "// SPDX-License-Identifier: AGPL-3.0-or-later\n" +
                "// Copyright (C) 2026 Jaeseong Song\n" +
                "// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.\n\n",
            "package "
        )
    }
    format("sqlScripts") {
        target(files(moduleSources.map { source -> fileTree(source.resolve("main/resources")) { include("**/*.sql") } }))
        // Every script starts with a "--" comment of its own: the delimiter is the first "--" that is not one of the three header lines, so that comment is kept.
        licenseHeader(
            "-- SPDX-License-Identifier: AGPL-3.0-or-later\n" +
                "-- Copyright (C) 2026 Jaeseong Song\n" +
                "-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.\n\n",
            "--(?! SPDX-License-Identifier| Copyright \\(C\\)| See ADDITIONAL-TERMS)"
        )
    }
}

// A Gradle service a build script takes by injection, for the work a task does when it runs (FileSystemOperations: the isolated test folders are emptied before a test run, D-0194).
interface InjectedFileSystem {
    @get:javax.inject.Inject
    val fileSystem: FileSystemOperations
}

val springBootVersion = "4.1.2"
// Spring AI is outside Boot's BOM and has one of its own: the starter and the MCP SDK it brings take their versions from it.
val springAiVersion = "2.0.2"
// JGit is in neither BOM, and the JDK has no Git client (a git process is not allowed), so its version is stated here once: the newest release of the 7.x line
// (Java 17+) that is older than two weeks (2026-09-01). The modules that use it declare it without a version.
val jgitVersion = "7.8.0.202609011348-r"

// The sizes of the build and of the test JVMs are Gradle's own unless a property says otherwise: this build states no heap, no processor count and no worker count of its
// own. A computer that several builds share states them for itself, in its own ~/.gradle/gradle.properties or on the command line (org.gradle.workers.max,
// org.gradle.jvmargs, -Psennard.test.heap, -Psennard.test.cpus, -Psennard.test.childHeap), and may switch the heap ledger on (below).
//
// The heap Gradle gives a test JVM when none is stated: https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html (maxHeapSize). It is the heap
// a child JVM of a test gets when none is stated, and the number the heap ledger counts for a Test or JavaExec task that states none (an Exec task counts -Psennard.exec.heap).
val gradleForkHeap = "512m"
val testHeapProperty: String? = providers.gradleProperty("sennard.test.heap").orNull
val testHeap: String = testHeapProperty ?: gradleForkHeap
// The child JVMs a test starts with java itself get -Xmx from the system property sennard.test.childHeap (set below on every Test task: -Psennard.test.childHeap, else the heap of the
// test JVM). The most such JVMs one test class holds at once is two (a stand-in or relay JVM and the JVM it starts, or two commands of a skill-run test). The tests that run the
// release jar's installed programs start the product's own JVMs, whose heaps the product fixes and the tests do not override: the hook command's 256m (JvmOptions.hookClient) and, when the hook
// starts the server, the default preset's 512m (JvmPreset.G1_512M): 768 MiB at once in HookStartsServerProcessTest. A class holds either kind, so a test JVM's children are the larger
// of the two sums; the heap ledger counts them so (testClaimMiB in SennardBuildLogicPlugin).
val childHeap: String = providers.gradleProperty("sennard.test.childHeap").getOrElse(testHeap)

// The heap ledger (SennardBuildLogicPlugin of build-logic, with HeapLedger) is off: the javadoc of the plugin describes the properties that switch it on. It counts these three values.
// They are stated here, in the body of this script, which Gradle evaluates before the other projects: the plugin reads them when it configures a task and when the task runs.
sennardHeapLedger.gradleForkHeap = gradleForkHeap
sennardHeapLedger.testHeap = testHeap
sennardHeapLedger.childHeap = childHeap

/** The variables the host agents set in their session (what a test must not see): SENNARD*, CLAUDE*, CODEX*, GEMINI*, ANTHROPIC*, OPENAI*. */
val hostSessionVariable = Regex("(SENNARD|CLAUDE|CODEX|GEMINI|ANTHROPIC|OPENAI).*", RegexOption.IGNORE_CASE)

// The tests that use the business schema need a PostgreSQL database the person running them names (BusinessDb in the test fixtures of sennard-store-pg). Those tests carry the JUnit
// tag below, and a module that has any (modulesOnBusinessDb) has two test tasks: `test` leaves them out, and `businessDbTest` runs only them and fails, before it starts
// anything, when the connection is not stated: -Psennard.test.businessDb.url=<jdbc url>, .user=<account> and .passwordFile=<file whose first line is the password>. No other task runs
// them and nothing falls back to a database of a computer: a run without a database neither connects to one nor skips a test.
// The tag is a JUnit tag of the tests (BusinessDb.TAG); the modules read it from here.
val businessDbTag = "business-db"
extra["businessDbTag"] = businessDbTag
val modulesOnBusinessDb = setOf("sennard-store-pg", "sennard-server", "sennard-personal")
val businessDbSettings = listOf("url", "user", "passwordFile")
// The database is one resource whose schemas every test class drops and migrates anew, so the work that uses it is one at a time: this build service allows one user, and a
// businessDbTest task holds it for its whole run, in one JVM, one test class after the other.
abstract class SharedBusinessDb : BuildService<BuildServiceParameters.None>

val sharedBusinessDb = gradle.sharedServices.registerIfAbsent("sharedBusinessDb", SharedBusinessDb::class.java) {
    maxParallelUsages = 1
}

allprojects {
    group = "io.github.jaeseongs95.sennard"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "implementation"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
        constraints {
            add("implementation", "org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
            add("runtimeOnly", "org.eclipse.jgit:org.eclipse.jgit.ssh.apache:$jgitVersion")
        }
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // The test fixtures of a module that has them (java-test-fixtures) are a source set of their own: testFixturesApi/testFixturesImplementation do not extend implementation, so the BOMs
    // above do not reach their classpaths and a dependency declared without a version (spring-tx, jackson) is not found. The same two platforms are put on that configuration:
    // https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures
    pluginManager.withPlugin("java-test-fixtures") {
        dependencies {
            "testFixturesImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
            "testFixturesImplementation"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
        options.compilerArgs.add("-Werror")
    }

    val onBusinessDb = project.name in modulesOnBusinessDb

    tasks.named<Test>("test") {
        useJUnitPlatform {
            if (onBusinessDb) {
                excludeTags(businessDbTag)
            }
        }
    }

    // Isolation of a test run from the machine and the session that starts Gradle (Test.environment, Test.systemProperty:
    // https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html). A forked JVM inherits the environment of the Gradle process, so
    // without this a test could reach the MCP server and relay of the agent session that ran the build, and the real home folder (~/.claude, ~/.gemini).
    // The folders are per task under the module's build folder, so working copies and modules that run at the same time never share a file.
    val isolatedFolders = layout.buildDirectory.dir("isolated")

    fun <T> T.isolateFromHost() where T : Task, T : JavaForkOptions {
        environment.keys.removeIf { hostSessionVariable.matches(it) }
        val home = isolatedFolders.get().dir("$name/home").asFile
        val temporary = isolatedFolders.get().dir("$name/tmp").asFile
        // The product reads its places from the user.home property (set below: it does not follow HOME or USERPROFILE) and from SENNARD_INSTALL_ROOT and
        // SENNARD_STATE_DIR; the child JVMs a test starts inherit the environment, so all of these name folders of the test run and none reaches the real profile.
        val install = isolatedFolders.get().dir("$name/install").asFile
        val state = isolatedFolders.get().dir("$name/state").asFile
        val folders = mapOf(
            "HOME" to home, "USERPROFILE" to home, "SENNARD_INSTALL_ROOT" to install, "SENNARD_STATE_DIR" to state,
            "TEMP" to temporary, "TMP" to temporary, "TMPDIR" to temporary,
        )
        folders.forEach { (variable, folder) ->
            environment.keys.removeIf { it.equals(variable, ignoreCase = true) } // Windows names are case-insensitive
            environment(variable, folder.absolutePath)
        }
        // The isolated folders lie inside this checkout, whose .git would make git (and the product's workspace facts) see them as part of the repository; a
        // test that needs a folder outside any repository (ToolGoldenTest's profile fixture) expects that of the OS temporary folder. A git ceiling stops
        // the search for a repository at the isolated folders (https://git-scm.com/docs/git#Documentation/git.txt-codeGITCEILINGDIRECTORIEScode).
        environment.keys.removeIf { it.equals("GIT_CEILING_DIRECTORIES", ignoreCase = true) }
        environment("GIT_CEILING_DIRECTORIES", isolatedFolders.get().asFile.absolutePath)
        // HOME above hides the user's git configuration, but not the system one (Git for Windows keeps core.autocrlf there), which would reach every temporary
        // repository a test makes and print a line-ending warning per file. GIT_CONFIG_NOSYSTEM stops git from reading it
        // (https://git-scm.com/docs/git#Documentation/git.txt-codeGITCONFIGNOSYSTEMcode).
        environment.keys.removeIf { it.equals("GIT_CONFIG_NOSYSTEM", ignoreCase = true) }
        environment("GIT_CONFIG_NOSYSTEM", "1")
        // user.home and java.io.tmpdir come from the OS profile, not from the variables above (a child JVM does not inherit a system property, so the product
        // takes its places from SENNARD_INSTALL_ROOT and SENNARD_STATE_DIR there).
        systemProperty("user.home", home.absolutePath)
        systemProperty("java.io.tmpdir", temporary.absolutePath)
        // A test run uses no screen: the test JVM is headless (java.awt.headless, JDK), and the servers it starts, which are child JVMs and do not get a system property, are told by the
        // environment they inherit that the product shows no tray icon (SENNARD_TRAY_ENABLED, ServerTray.ENABLED_SETTING). Without it every run put an icon on the user's taskbar.
        systemProperty("java.awt.headless", "true")
        environment.keys.removeIf { it.equals("SENNARD_TRAY_ENABLED", ignoreCase = true) }
        environment("SENNARD_TRAY_ENABLED", "false")
        // The folders of an earlier run are emptied before this one starts (D-0194): a test run that left its temporary files behind would otherwise add them to the next
        // run's, and the folder grew by every run. The work is Gradle's injectable FileSystemOperations.delete (a file it cannot delete fails the task, it is not skipped; it also
        // deletes the read-only files git makes under .git/objects, which File.deleteRecursively of the Kotlin standard library does not on Windows). The lambda holds this service
        // and local files, no project object, which the configuration cache could not store
        // (https://docs.gradle.org/current/userguide/configuration_cache_requirements.html, "Requirements" > "Using the injected services").
        val fileSystem = objects.newInstance<InjectedFileSystem>().fileSystem
        val ownFolders = listOf(home, temporary, install, state)
        val allFolders = folders.values.toList()
        doFirst {
            fileSystem.delete { delete(ownFolders) }
            allFolders.forEach { it.mkdirs() }
        }
    }

    tasks.withType<Test>().configureEach {
        isolateFromHost()
        // A test run is run again and not replayed from the build cache (org.gradle.caching): the tests start real processes and servers, use ports and the machine's PostgreSQL,
        // and their point is that they ran here and now. Task.outputs.doNotCacheIf: https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html
        outputs.doNotCacheIf("a test run is run, not replayed: it starts processes and reads the machine") { true }
    }
    if (onBusinessDb) {
        // Sennard ships no database driver: the user gives the product a jar (the driver path of the link on the admin page), and so do the tests. The one database the tests run on (PostgreSQL,
        // development principle 7.3) has its driver here, in a configuration that is on no class path (a driver on the class path would be found without the path the product is given,
        // and hide the thing under test); the task below copies the jar into one folder, which is the driver path of the tests unless -Psennard.test.businessDb.driverPath names another jar or folder.
        // The version is the Spring Boot BOM's (postgresql), and only the driver itself is taken (no dependency of its).
        val businessDbDrivers = configurations.create("businessDbDrivers") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies {
            "businessDbDrivers"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
            "businessDbDrivers"("org.postgresql:postgresql") { isTransitive = false }
        }
        val businessDbDriverFolder = layout.buildDirectory.dir("business-db-drivers")
        val copyBusinessDbDrivers = tasks.register<Sync>("copyBusinessDbDrivers") {
            description = "Copies the JDBC driver of the database the business-db tests run on into one folder, the driver path of those tests."
            from(businessDbDrivers)
            into(businessDbDriverFolder)
        }
        val businessDbDriverPath = providers.gradleProperty("sennard.test.businessDb.driverPath").orElse(businessDbDriverFolder.map { it.asFile.absolutePath })
        val sourceSets = extensions.getByType<SourceSetContainer>()
        // The settings are read at configuration, so that the task's check below holds plain values (a reference to the script object cannot be stored by the configuration cache).
        val statedSettings = businessDbSettings.associateWith { providers.gradleProperty("sennard.test.businessDb.$it").orNull }
        val missingSettings = statedSettings.filterValues { it == null || it.isBlank() }.keys.map { "-Psennard.test.businessDb.$it" }
        tasks.register<Test>("businessDbTest") {
            description = "Runs the tests tagged $businessDbTag on the PostgreSQL database named by -Psennard.test.businessDb.url, .user and .passwordFile; fails when one is not given. " +
                "The JDBC driver is found in -Psennard.test.businessDb.driverPath (a jar or a folder), by default the folder of the drivers this build copies."
            group = "verification"
            testClassesDirs = sourceSets["test"].output.classesDirs
            // The classpath is asked of the source set when the task is used, not now: a module that applies java-test-fixtures (sennard-store-pg) replaces the test source set's runtimeClasspath
            // in its own script, which is evaluated after this block (JavaTestFixturesPlugin.createImplicitTestFixturesDependencies: "in order to avoid duplicate classes", gradle/gradle#10872),
            // and the collection read here earlier would still hold the main output folders next to the module's main jar that testFixtures brings, so every classpath resource of the
            // module (the migration scripts) was found twice. Project.files(Object...) resolves a Callable at the time the file collection is used
            // (https://docs.gradle.org/current/javadoc/org/gradle/api/Project.html#files(java.lang.Object...)); `test` gets the same collection by the Java plugin's convention.
            classpath = files(java.util.concurrent.Callable { sourceSets["test"].runtimeClasspath })
            useJUnitPlatform { includeTags(businessDbTag) }
            shouldRunAfter(tasks.named("test"))
        }
        // Every task of the module that runs a test on the business database: businessDbTest, and the measurement perfBaseline of sennard-personal, whose server runs on it.
        tasks.withType<Test>().matching { it.name == "businessDbTest" || it.name == "perfBaseline" }.configureEach {
            usesService(sharedBusinessDb)
            dependsOn(copyBusinessDbDrivers)
            systemProperty("sennard.test.businessDb.driverPath", businessDbDriverPath.get())
            maxParallelForks = 1
            // What the tests read is a database, not a file of this build: a run with nothing changed still has to ask it.
            outputs.upToDateWhen { false }
            // The one business-database pool of the test JVM is made by a JUnit extension of the sennard-store-pg fixtures (BusinessDbExtension), which the engine finds by its
            // META-INF/services entry once extension autodetection is on (JUnit User Guide, "Extension Registration", "Automatic Extension Registration").
            systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
            statedSettings.forEach { (key, value) ->
                if (value != null && value.isNotBlank()) {
                    systemProperty("sennard.test.businessDb.$key", value)
                }
            }
            // The first action: a request for these tests without the connection is a failure, never a run that skips them.
            doFirst("requireBusinessDb") {
                if (missingSettings.isNotEmpty()) {
                    throw GradleException("${this.name} needs a PostgreSQL database and none is given: missing ${missingSettings.joinToString(", ")}. " +
                        "Give -Psennard.test.businessDb.url=<jdbc url> -Psennard.test.businessDb.user=<account> -Psennard.test.businessDb.passwordFile=<file>")
                }
            }
        }
    }

    tasks.withType<Test> {
        // -Psennard.test.heap=<size> states the heap of a test JVM; without it Gradle's own default applies.
        // The same value goes to the test JVM as sennard.test.statedHeap, so that a test checks the heap against what the build stated, not against a copy of it.
        testHeapProperty?.let {
            maxHeapSize = it
            systemProperty("sennard.test.statedHeap", it)
        }
        // -Psennard.test.cpus=<n> states the processors a test JVM sees (Test.jvmArgs: "Testing in Java & JVM projects", JDK -XX:ActiveProcessorCount); without it the JVM sees them all.
        // The measurement task perfBaseline is not a test run: it is measured with the shipped server's options, so it is left out.
        if (name != "perfBaseline") {
            providers.gradleProperty("sennard.test.cpus").orNull?.let { jvmArgs("-XX:ActiveProcessorCount=$it") }
        }
        // Tests that start real processes use the same classes.
        systemProperty("sennard.test.childHeap", childHeap)
        doFirst { systemProperty("sennard.test.classpath", classpath.asPath) }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

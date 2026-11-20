// The machine checks of the 6.0.0 design rules (docs/sennard60-architecture-checks.md). Test sources only: nothing depends on this module and it ships nothing.
// It lives in its own module because the checks read every module's sources and compiled classes, and none of the product modules should have to
// build the app image or start PostgreSQL to run them. Run one check class alone: ./gradlew :sennard-arch-checks:test --tests '*ArchitectureRulesTest'
plugins {
    java
}

dependencies {
    // Package dependency analysis of the compiled classes. Not in the Spring Boot BOM (spring-boot-dependencies-4.1.2.pom has no com.tngtech entry), so the
    // version is pinned here: 1.5.1 is the release that lists Java 25 class files as supported since 1.4.1 (ArchUnit release notes, 1.4.1 "Support Java 25").
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    // The hook definitions (JSON) and the CI workflows (YAML) are read as token streams so that a violation has its line. Versions come from the Boot BOM (jackson-bom 3.x).
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
}

val moduleNames = rootProject.subprojects.map { it.name }.sorted()

// The Spring Boot BOM's own pom, resolved by Gradle: the checks read it to know which libraries Boot already manages (a version written next to such a library is a violation).
val bootBomPom = configurations.create("bootBomPom") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val bootVersion = Regex("""val springBootVersion = "([^"]+)"""").find(rootProject.file("build.gradle.kts").readText())?.groupValues?.get(1)
    ?: throw GradleException("build.gradle.kts of the root no longer defines springBootVersion; the BOM pom of the checks cannot be chosen")
dependencies {
    bootBomPom("org.springframework.boot:spring-boot-dependencies:$bootVersion@pom")
}

tasks.named<Test>("test") {
    // Sources, SQL and build scripts of every module are read as files; the compiled classes of every other module are read by ArchUnit.
    val others = moduleNames.filter { it != project.name }
    others.forEach { dependsOn(":$it:classes") }
    inputs.files(fileTree(rootDir) {
        include("*/src/**", "*/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts")
        exclude("**/build/**")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    // The repository rules read files outside the modules: the hook definitions (hooks.json, hooks/hooks.json, claude-plugin/hooks/hooks.json, claude-overlay/hooks/hooks.json),
    // the workflows (.github/workflows) and, to find a script executable, every file of the repository. The whole tree is an input so that adding a script anywhere re-runs the checks.
    val repositoryRoot = rootDir.parentFile
    inputs.files(fileTree(repositoryRoot) {
        exclude("**/.git/**", "**/build/**", "**/.gradle/**", "**/node_modules/**")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(bootBomPom)
    systemProperty("sennard.arch.modules", moduleNames.joinToString(",") { rootDir.resolve(it).absolutePath })
    systemProperty("sennard.arch.root", rootDir.absolutePath)
    systemProperty("sennard.arch.repository", repositoryRoot.absolutePath)
    systemProperty("sennard.arch.classes", others.joinToString(",") { project(":$it").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath })
    // The pom is resolved when the task runs. The lambda holds only this local file collection: a reference to the script's own property would be a Gradle script object
    // reference, which the configuration cache cannot store (https://docs.gradle.org/current/userguide/configuration_cache_requirements.html).
    val bootBomFiles = bootBomPom.incoming.files
    doFirst { systemProperty("sennard.arch.boot-bom", bootBomFiles.singleFile.absolutePath) }
}

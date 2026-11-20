// Build logic of the Sennard build in Java (the heap sizes and the opt-in heap ledger), an included build that provides a plugin to the root project (java/settings.gradle.kts,
// pluginManagement.includeBuild): https://docs.gradle.org/current/userguide/composite_builds.html#included_plugin_builds. It is not buildSrc, whose tests no task of
// the build runs. Its tests run in the root build (sennard-repo-tools compiles them with the classes they test), not as a Test task of this build, which the root build's limit on the
// tasks that reserve heap does not reach (D-0270); this build's own `test` is for a run of this build alone (java/build-logic, one process), and it reserves nothing.
plugins {
    `java-library`
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        register("sennardBuildLogic") {
            id = "io.github.jaeseongs95.sennard.build-logic"
            implementationClass = "io.github.jaeseongs95.sennard.build.SennardBuildLogicPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The JUnit line the application modules get from Spring Boot's BOM (java/build.gradle.kts, springBootVersion).
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-Werror")
}

tasks.test {
    useJUnitPlatform()
}

// The heap of a Test task is Gradle's own unless -Psennard.test.heap states one (java/build.gradle.kts states the same properties for the application modules).
val testHeapProperty: String? = providers.gradleProperty("sennard.test.heap").orNull
tasks.withType<Test>().configureEach {
    testHeapProperty?.let { maxHeapSize = it }
}

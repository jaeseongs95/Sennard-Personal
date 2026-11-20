import java.security.MessageDigest

// The release: the Boot jar and its sha256 (<hex>  <name>, what sha256sum --check reads) in build/release. A script plugin of build.gradle.kts, so that ReleaseJarTaskBuildTest runs this very
// text in a small build of its own (the jar of the repository is too large to build in a test).
// releaseJar is a Sync task (https://docs.gradle.org/current/dsl/org.gradle.api.tasks.Sync.html): its input is the bootJar's archive and its output is the whole release folder, so a
// folder that an earlier build filled is brought to the same content (the old jar replaced, anything else removed) and the task is up to date when nothing changed. The checksum file is
// written by the task's last action, which is part of the task's output; the jar is hashed in chunks, not read into memory whole.
val releaseDirectory = layout.buildDirectory.dir("release")
val releaseJarName = "sennard-${project.version}.jar"
val bootArchive = tasks.named<AbstractArchiveTask>("bootJar").flatMap { it.archiveFile }

tasks.register<Sync>("releaseJar") {
    description = "Copies the release jar to build/release and writes its sha256 beside it."
    group = "distribution"
    from(bootArchive)
    into(releaseDirectory)
    // Local values only: the action holds no script or project object, which the configuration cache cannot store.
    val directory = releaseDirectory.get().asFile
    val name = releaseJarName
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.resolve(name).inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        directory.resolve("$name.sha256").writeText("$hex  $name\n", Charsets.UTF_8)
    }
}

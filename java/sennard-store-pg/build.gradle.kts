plugins {
    `java-library`
    // BusinessDb, the fixture of the shared business database, is used by the tests of sennard-server and sennard-personal too.
    `java-test-fixtures`
}

dependencies {
    api(project(":sennard-api"))
    // The one place an instant becomes a TIMESTAMP value and back (Timestamps).
    implementation(project(":sennard-domain"))
    // The migration runner (HistoryMigrator), which the settings store uses as well; MigrationRunner of this module is its adapter.
    implementation(project(":sennard-migration"))
    // The version comes from the Spring Boot 4.1.2 BOM (HikariCP 7.0.2). The migrations are the runner of sennard-migration (HistoryMigrator), not Flyway. No JDBC driver is a dependency of
    // this module or of the product: the user gives the driver of the database as a jar (JdbcDrivers); the tests get theirs from the businessDbDrivers configuration of java/build.gradle.kts.
    api("com.zaxxer:HikariCP")
    // TransactionManagerCustomizers: the edition's spring.transaction.* settings reach the store's transaction manager the way Boot's own reach its manager (BOM-managed version).
    implementation("org.springframework.boot:spring-boot-transaction")
    // Rewriting one field of a stored JSON record keeps key order and number text (BOM-managed version).
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":sennard-core"))
    // The repositories and services of the rewritten areas (board, messaging) run against a real PostgreSQL (businessDbTest).
    testImplementation(project(":sennard-app"))
}

// The fixtures take their versions from the BOMs the root build puts on implementation (the main module's dependencies carry none).
configurations.named("testFixturesImplementation") {
    extendsFrom(configurations["implementation"])
}

// BusinessDbExtension, the one business-database pool of a test JVM, is a JUnit extension of the fixtures (version from the Spring Boot BOM).
dependencies {
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    // The stand-in of the port of the storage of secrets (TestPrivateStorage): the business-database pool of the fixtures and the tests of this module have no edition.
    testFixturesImplementation(testFixtures(project(":sennard-api")))
    testImplementation(testFixtures(project(":sennard-api")))
}

// The tests of the business schema run on a PostgreSQL database the person running them names (BusinessDb): the task businessDbTest, with
// -Psennard.test.businessDb.url|user|passwordFile=<value> and optionally .driverPath=<jar or folder> (java/build.gradle.kts). `test` leaves the tests tagged business-db out. The build serializes the tasks that use the
// database (sharedBusinessDb), and each test class drops the sennard schema and migrates it anew.

// The candidate of the cs-engineering example (skills/cs-engineering/assets/examples/claim-queue/candidate) is compiled with the tests: CsQueueReferenceTest runs the
// cases of the cards against it, so the file that is tested is the file the example's candidate.json and evidence name by digest. The output file of the
// evidence (-Psennard.cs.evidence.out=<file>, written only when asked) is how the example's evidence is made again.
val csExample = rootDir.parentFile.resolve("skills/cs-engineering/assets/examples/claim-queue")
sourceSets.named("test") {
    java.srcDir(csExample.resolve("candidate"))
}

// The cs-engineering example is run by CsQueueReferenceTest, which is tagged business-db: businessDbTest, and `test` with the tag left out (root build).
listOf("test", "businessDbTest").forEach { taskName ->
    tasks.named<Test>(taskName) {
        systemProperty("sennard.cs.example", csExample.absolutePath)
        systemProperty("sennard.cs.test.script", layout.projectDirectory.file("src/test/java/io/github/jaeseongs95/sennard/service/store/pg/CsQueueReferenceTest.java").asFile.absolutePath)
        providers.gradleProperty("sennard.cs.evidence.out").orNull?.let { systemProperty("sennard.cs.evidence.out", it) }
    }
}

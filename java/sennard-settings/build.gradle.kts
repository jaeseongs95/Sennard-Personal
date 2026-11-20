// Sennard's own settings store (decision 14, 14-1, 14-2): an H2 file database under the state folder that holds what the server needs before the business database is linked and
// what must never be in it: the passkey public keys, the signed admin records of the time before the link, and the link settings. It is not a business database and no
// business data goes in it. The contract (interfaces and records) is in sennard-api (io.github.jaeseongs95.sennard.service.settings); this module is the H2 implementation, and only an
// assembly that wants that implementation (sennard-personal) depends on it.
plugins {
    `java-library`
}

dependencies {
    api(project(":sennard-api"))
    // Digests (the migration scripts' SHA-256) and Timestamps (the one place an instant becomes a TIMESTAMP value and back).
    implementation(project(":sennard-domain"))
    // The one migration runner of the product (HistoryMigrator); SettingsMigrator is its adapter. The business database's migrations (sennard-store-pg) use the same one.
    implementation(project(":sennard-migration"))
    // The DataSource builder and HikariCP, spring-jdbc's JdbcClient and the @ConfigurationProperties binding; versions from Boot's BOM.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // H2 is Sennard's own settings database, not a database the user links, so its driver is a dependency of this module (version from Boot's BOM, not pinned).
    implementation("com.h2database:h2")
    // ApplicationContextRunner: the tests start the settings beans beside a stand-in business DataSource and Boot's JDBC auto-configuration.
    // (Its AssertJ is what ApplicationContextRunner's result type needs to compile; the tests assert with JUnit.)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The stand-in of the port of the storage of secrets (TestPrivateStorage): this module has no edition.
    testImplementation(testFixtures(project(":sennard-api")))
}

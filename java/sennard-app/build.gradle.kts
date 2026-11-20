// The application layer of Sennard: the @Service classes that open the transactions, the Spring Data JDBC repositories of the areas already rewritten
// (board, messaging), the @ConfigurationProperties records and the one message source. It depends on sennard-domain (rules, records, errors) and on Spring;
// it knows no MCP, no HTTP and no operating system. The adapters (sennard-server for MCP and HTTP, sennard-store-pg for the database assembly) depend on it.
plugins {
    `java-library`
}

dependencies {
    api(project(":sennard-domain"))
    // Only for StoreException, which the transaction manager's commit translation still throws (PersistenceErrors reads its code). Goes with the old stores in phase 5.
    implementation(project(":sennard-api"))
    // Spring Data JDBC with Boot's auto-configuration (the DataSource, JdbcTemplate and repositories come from spring.datasource.* and the BOM's versions).
    api("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Jakarta Validation 3.1 with Hibernate Validator, the one Boot ships: @ConfigurationProperties and @Validated services use it.
    api("org.springframework.boot:spring-boot-starter-validation")
    // The message source reads the catalogs with the context's JsonMapper bean, which Boot's Jackson auto-configuration supplies; a plain jackson-databind dependency would
    // leave an application that has only this module with no such bean.
    api("org.springframework.boot:spring-boot-starter-jackson")
    // The converters of Instant to a TIMESTAMP column are tried through Spring Data JDBC on an embedded H2 (version from Boot's BOM): a real driver, no mock. Test only.
    testRuntimeOnly("com.h2database:h2")
    // The stand-in of the port of the storage of secrets (TestPrivateStorage): this module has no edition, and sennard-personal depends on it, not the other way round.
    testImplementation(testFixtures(project(":sennard-api")))
}

tasks.withType<JavaCompile> {
    // Spring Data JDBC names the parameters of a @Query from the bytecode. Modules with the Boot plugin get this flag; a plain library does not.
    options.compilerArgs.add("-parameters")
}

// The wording is the repository's catalogs (locales/), shipped inside this module so that every assembly that has the message source has its text.
tasks.named<ProcessResources>("processResources") {
    from("../../locales") {
        into("locales")
        exclude("**/translations.lock.json")
    }
}

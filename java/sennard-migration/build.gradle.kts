// The one migration runner of the product: the scripts of a jar (V<number>__<description>.sql) applied in number order, once each, with a history table of ANSI types
// (HistoryMigrator). Two places apply scripts to a database, the business database (sennard-store-pg) and the H2 settings store (sennard-settings); both are adapters of this module
// and neither owns a runner of its own, so the history format and the rules that judge it are the same in both. It replaces Flyway, which keeps a list of the databases it knows
// and brings a part for each; this module holds no DBMS name (the product's spelling of the two phrases that differ is ScriptDialect, chosen from the driver's product name).
// The module depends on the contract (sennard-api: SqlBuilder.Database) and on the domain's digests and timestamps, and on nothing that is a database or an edition.
plugins {
    `java-library`
}

dependencies {
    // SqlBuilder.Database, which ScriptDialect is chosen by; spring-jdbc (JdbcClient, ResourceDatabasePopulator) and spring-tx (DuplicateKeyException) are sennard-api's api dependencies.
    api(project(":sennard-api"))
    // Digests (the scripts' SHA-256) and Timestamps (the one place an instant becomes a TIMESTAMP value).
    implementation(project(":sennard-domain"))
    // The logger of the runner (version from the Spring Boot BOM).
    implementation("org.slf4j:slf4j-api")
}

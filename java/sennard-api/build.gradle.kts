// The public surface the private editions compile against: plain interfaces and records, plus the transaction contract,
// which is Spring's: @Transactional from spring-tx and JdbcClient from spring-jdbc. A Boot major version change is a major change of this module.
plugins {
    `java-library`
    // TestPrivateStorage, the one stand-in of the port PrivateStorage for the tests of the modules that have no edition (sennard-app, sennard-settings, sennard-store-pg). It lives with the port it
    // stands in for, so that it keeps what the port's contract says in one place (the three copies each kept a part of it).
    `java-test-fixtures`
}

dependencies {
    api(project(":sennard-protocol"))
    // The port of the storage of secrets (PrivateStorage) is written in the domain's SecretValue and refuses with the domain's UnavailableException.
    api(project(":sennard-domain"))
    api("org.springframework:spring-tx")
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-context")
    // Resolve supported products from JDBC metadata.
    implementation("org.springframework.boot:spring-boot-jdbc")
    testImplementation("com.zaxxer:HikariCP")
    testRuntimeOnly("com.h2database:h2")
}

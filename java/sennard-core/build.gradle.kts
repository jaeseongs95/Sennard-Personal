// The product rules of the session identity and the dispatcher. It depends on the public contract only; the database is reached through
// the store's JdbcClient.
plugins {
    `java-library`
}

dependencies {
    api(project(":sennard-api"))
    // The one place an instant becomes a TIMESTAMP value and back (Timestamps).
    implementation(project(":sennard-domain"))
}

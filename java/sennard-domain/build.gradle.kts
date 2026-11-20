// The business rules of Sennard as plain Java: judgements, value records, enums, sealed variants and the error codes. Nothing here knows
// Spring, JDBC or Jackson's databind; ArchitectureRulesTest (rule o-domain-imports) fails the build when a source of this module imports them.
// The module has no dependency on purpose: the JDK is what a rule needs. A library is added here only for annotations a record carries
// (jackson-annotations, jakarta.validation-api), never for something that runs.
plugins {
    `java-library`
}

dependencies {
    // The glossary's enums carry their JSON names as annotations (the version is the Spring Boot BOM's).
    api("com.fasterxml.jackson.core:jackson-annotations")
}

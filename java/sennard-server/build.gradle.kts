// The web skeleton every edition assembles: the MCP endpoint from the Spring AI starter on embedded Tomcat and the one
// filter in front of every path. The personal edition's main class lives in sennard-personal; this module has no entry point.
plugins {
    `java-library`
}

dependencies {
    api(project(":sennard-api"))
    api(project(":sennard-core"))
    api(project(":sennard-store-pg"))
    api(project(":sennard-app"))
    // The contract the server shares with the hook client (HookClientCall, HookEndpoints, HookHeaders): the client is JDK only, and it is where the contract is written.
    api(project(":sennard-hook-client"))
    api("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // Bounded in-memory caches (Spring Cache's Caffeine provider). The starter brings spring-boot-cache, whose CacheAutoConfiguration builds the cache manager.
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // The JEV provider's HTTP clients (TypeSafeClientConfiguration): the starter brings RestClientAutoConfiguration, whose RestClient.Builder bean the clients are
    // made from, and spring-boot-http-client (ClientHttpRequestFactoryBuilder, HttpClientSettings). Spring Boot 4 splits these from the web MVC starter.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework:spring-tx")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
}

dependencies {
    // The glossary tests run its tables on a PostgreSQL database (BusinessDb, businessDbTest), made by the migrations of sennard-store-pg.
    testImplementation(testFixtures(project(":sennard-store-pg")))
}

listOf("test", "businessDbTest").forEach { taskName ->
    tasks.named<Test>(taskName) {
        // The glossary tests run the migration scripts of sennard-store-pg from the repository.
        systemProperty("sennard.repo.root", rootDir.parentFile.absolutePath)
        inputs.dir(rootDir.parentFile.resolve("java/sennard-store-pg/src/main/resources/db/expand/glossary"))
            .withPropertyName("glossaryScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

tasks.withType<JavaCompile> {
    // @McpTool parameters are named from the bytecode. Modules with the Boot plugin get this flag; a plain library does not.
    options.compilerArgs.add("-parameters")
}

// The skills' data files ship inside the server: contracts, catalogs, registries. Scripts and instructions do not. The wording (locales/) ships in
// sennard-app, whose message source is the only reader of it.
tasks.named<ProcessResources>("processResources") {
    from(rootDir.parentFile) {
        include("skills/**", "contracts/**", "runtime/model-routing-presets.json")
        exclude("**/*.md", "**/LICENSE", "**/agents/**", "**/SKILL.md")
        into("sennard-skills")
    }
    // The one instruction text the server itself speaks (the MCP instructions of initialize).
    from(rootDir.parentFile) {
        include("skills/orchestrator/references/intake.md")
        into("sennard-skills")
    }
    // The wording (locales/) ships in sennard-app, which owns the message source.
    from("../../contracts") {
        into("contracts")
        include("*.schema.json")
    }
    from(rootProject.file("../skills/orchestrator/contracts/collaboration-decision.v1.schema.json")) { into("contracts") }
}

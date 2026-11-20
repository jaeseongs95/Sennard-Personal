[한국어](settings-and-profiles.ko.md)

# Settings and Profiles

This document defines the conventions for how configuration values are received and how what differs between deployments is separated. The reference implementations are the status board and session messaging. For the profile part, only the convention has been decided and it is not yet implemented (marked below).

## Starting Points

- Spring documentation: "Provide choice at every level. Spring lets you defer design decisions as late as possible ... through configuration without changing your code." Differences between environments are placed in configuration, not in business code.
- Boot reference documentation, Externalized Configuration: `@ConfigurationProperties` is a typed binding, and it carries validation (`@Validated`) and defaults (`@DefaultValue`) together. Values are not read one by one with `@Value`.
- Order of judgment (is it in Spring, then Boot auto-configuration, then configuration, then a bean): the DataSource, the pool, the transaction manager, Jackson, the `MessageSource`, and the validator are assembled by Boot, so they are not created directly and are changed through `spring.*` settings.

## Rules

| Rule | Content |
| --- | --- |
| Values | Received as a `@ConfigurationProperties` record. `@Value` is not newly written (build rule `c-value-annotation`; the ceiling is currently 27 places, and it is reduced as areas are migrated). |
| Defaults | The `@DefaultValue` of a record component. `getProperty(key, default)` and constant defaults inside code are not used. |
| Constraints | The range of a value is declared in the record and checked at startup with `@Validated`. A value fixed in a public contract is prevented from being raised with `@Max`. |
| Time and size | Spring types such as `Duration` and `DataSize` are used. A unit is not attached by name to an integer of seconds (`retention: 7d`). |
| Clock, identifier, random number, JSON | Injected as beans: `Clock`, `IdGenerator`, `SecureRandom`, `JsonMapper` (Boot auto-configuration). `Clock.systemUTC()`, `new SecureRandom()`, and `JsonMapper.builder()` are not created directly in business code. |
| Environment variables and OS conventions | Not read. A required JVM standard value is received through a placeholder such as `${user.home}` in the yaml (build rules `c-getenv` and `c-getproperty`). |
| Deployment differences | Profiles. Business code does not branch on a profile name; a profile decides only the choice of implementation beans and the configuration values. |
| Enabling and disabling infrastructure | One configuration line plus a conditional bean (`@ConditionalOnProperty`). The disabled side is not filled with a mock bean. |

## Related Classes

| Role | Class |
| --- | --- |
| Area settings | `BoardProperties` (`sennard-app/.../board/BoardProperties.java:17-23`), `MessagingProperties` (`.../messaging/MessagingProperties.java:19-24`) |
| Binding registration | `AppConfiguration` (`sennard-app/.../app/AppConfiguration.java:27`, `@ConfigurationPropertiesScan`) |
| Injected beans | `Clock` (`AppConfiguration.java:52-55`), `IdGenerator` (`:58-61`), `MessageSource` (`:40-43`), `MessageSourceAccessor` (`:46-49`) |
| Boot's own settings | `spring.data.jdbc.dialect` (`sennard-personal/src/main/resources/application.yaml:13-18`) |
| Default settings file | `sennard-personal/src/main/resources/application.yaml` |

## How It Works

### Area Settings Record

```java
// BoardProperties.java:17-23
@ConfigurationProperties("sennard.board")
@Validated
public record BoardProperties(
    @DefaultValue("200") @Min(1) @Max(200) int summaryMaxLength,
    @DefaultValue("120") @Min(1) @Max(120) int roleMaxLength,
    @DefaultValue("7d") @NotNull Duration retention) { }
```

A service receives it through its constructor (`BoardService.java:40`). The configuration key is `sennard.<area>.<value>`, and in the yaml it is changed as `sennard.board.retention: 14d`. A test guards that the default equals the value of the public contract (`MessagingPropertiesTest`). A test replaces the `Clock` with a fixed clock and verifies expiry and retry without depending on time (`TestClock` in `RewrittenAreasContext`).

### Infrastructure Deferred at Startup: the Dialect

The business DB connection of the personal server may be configured after the listener has started. `application.yaml` does not specify a Spring Data JDBC dialect: the dialect follows the product of the connected business database. It is resolved from the connection the first time a repository needs it (`LazyJdbcRepositories`, `BusinessJdbcConfiguration`), so the user's JDBC URL and driver (the link on the admin page, kept in the settings database) alone choose the DBMS. Business code does not know the dialect.

### Defaults of the Starting Environment

- `spring.web.locale: en` (`application.yaml`): a request without `Accept-Language` is answered in the original language of the catalog (English), not in the language of the server machine ([Exceptions and Messages](exceptions-and-messages.md)).
- `management.health.db.enabled: false`: while the store is being prepared, the answer is `STORE_PREPARING`, so the DataSource health check is prevented from treating this as a server failure.

### Server JVM Options: One Preset

JVM options must be decided before the JVM starts, and the store of the server (the signed record) starts inside that JVM. Therefore a value goes through a single configuration file (`sennard-jvm.yaml`) to the JVM options that the installation starts the server with.

- **A preset is an enum** (`JvmPreset`, `sennard-domain`): G1 224m, 512m, 1g, 2g, 4g, ZGC 4g, and ZGC `MaxRAMPercentage=6`. Because there is no field that accepts an option string, even a signed change cannot insert an option outside this set (`JVM_PRESET_SET` accepts only a constant name).
- **The initial value is decided once by the installer** (`Installer.chooseFromMemory`). It reads `OperatingSystemMXBean#getTotalMemorySize` once (`HostMemory`), `JvmPresets.estimate` rounds it up to a standard size, and then the preset of the largest tier at or below that is selected. The value read and the preset are written to `sennard-jvm.yaml` (`sennard.jvm.preset`, `sennard.jvm.observed-memory`). The server does not measure memory at startup. Nothing is written to `sennard.yaml`, which people write (it is separated from the file the program rewrites).
- **The JVM options are written in one place** (`JvmOptions`). They cover the server (UTC, the preset, compact object headers, `-Xlog:gc*` file rotation with relative file names because the server runs with the installation folder as its working folder, the AOT cache automatic mode and the cache log), the training and cache-generation runs, and the hook client and the MCP relay that a host starts (the same small set, no JVM log, and the hook client's own AOT cache `sennard-hook.aot`, `JvmOptions.hookClient`; see `hooks.md`). The server is started as `<java> <options> -cp <application jar> SennardPersonalApplication`, with the `java` that ran `sennard install`. The `sennard` command itself is the user's `java -jar` and has no options of Sennard's.
- **The AOT cache is a per-collector file** (`app-g1.aot`, `app-zgc.aot`). An installation or upgrade creates only the cache of the selected collector. Because of `-XX:AOTMode=auto`, when the cache is absent or does not match, the JVM starts without the cache and writes the reason to `jvm-aot.log` (`JvmOptionsTest` verifies this by actually starting a JVM).
- **The log folder is the installation folder.** A JVM does not start if it cannot open its log file, so the log is placed in a folder that always exists.
- **The admin page changes it.** When a signed `JVM_PRESET_SET` is committed, `PersonalJvmInstallation` writes the file after the commit (`@TransactionalEventListener`). When the store becomes ready and the file differs from the signed value (a reinstallation), it is restored to the signed value (`StoreReadyTask`). A change takes effect only after the server is restarted.

### Profiles (Convention, Implemented in Stage 5)

The environment axis (the server and the tests on a personal PC) is divided with `application-{profile}.yaml` and `spring.profiles.group`. Several documents are not duplicated in one file; they are reduced with groups. A profile decides only (1) configuration values and (2) the choice of implementation beans (`@Profile` or a conditional bean). Currently `@Profile` is not used, and a single `application.yaml` is the value set of the personal server. The values of a run in which the DB is already up at startup (the DB address is `spring.datasource.*`, and the dialect is left empty) are placed in a profile file.

## Cautions

- Before making the value of a settings record raisable, check the public contract. A value that the contract forbids is blocked in advance with `@Max`.
- A `@ConfigurationProperties` record is bound only when it is registered with `@EnableConfigurationProperties` or `@ConfigurationPropertiesScan` (`AppConfiguration.java:27` scans under `sennard.app`).
- When changing configuration in a test context, a property source is added to the context (`RewrittenAreasContext.java:126-`). System properties and environment variables are not changed.
- A configuration value is not held in a static field (build rule `d-stateful-static`).

## Usage Examples

- A settings record and a service: `BoardProperties.java:17-23` to `BoardService.java:40-46,59-61`.
- A test that changes a limit through configuration: `AbstractMessagingServiceTest.java` (the limit tests, which supply `MessagingProperties` with test values).
- The dialect configuration test: `theDialectIsStatedBySpringDataJdbcSettingAndDetectingItWouldNeedAConnectionAtStart` in `BoardServiceAppConnectionTest.java`.

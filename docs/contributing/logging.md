[한국어](logging.ko.md)

# Logging

This document defines the conventions for writing logs. It uses the logging library that Spring Boot provides as one bundle, and the level, format, and file are set by configuration.

## Starting Point

- Use the logging library that is included in the Spring Boot bundle, do not print with `System.out`, and do not print exception stack traces directly with `printStackTrace()`.
- Boot reference documentation, Logging: `spring-boot-starter-logging` (SLF4J API and Logback) is the default. The log level, pattern, file, and rotation are set by the `logging.*` properties.

## Rules

| Rule | Description |
| --- | --- |
| Logger | Use only the SLF4J `Logger` (`LoggerFactory.getLogger(class)`). Do not mix other logging APIs. |
| Prohibited | `System.out`, `System.err` (the only exception is the set of probes in tests where the standard output of a child process is itself the subject of the check), and `printStackTrace()`. The build rules `e-console-output` and `e-print-stack-trace` check this. |
| Exceptions | Pass the throwable as the last argument: `LOG.warn("description {}", value, failure)`. The logger writes the stack. Do not print only `e.getMessage()`. |
| No swallowing | A caught exception is either logged or rethrown with the cause attached. The build rule `f-swallowed-catch` checks this (the current ceiling is 147, to be reduced to 0 as areas are migrated). |
| Level and format | Not set in code; set by the `logging.*` properties and profiles. |
| Values | Do not put values such as bodies, paths, and tokens in logs. An exception message is only a code name (`getMessage()` of `SennardException`). |
| Secrets | A password, token, API key, signing key or pepper is held as `SecretValue` (`sennard-domain`), never as a `String`, `byte[]` or `char[]` field, record component or `@ConfigurationProperties` value. Its `toString()` is `SecretValue[redacted]`, so the text of a record that holds one, a log line and an exception message do not show it, and a JSON writer refuses it (the one JSON use is reading what a person typed). The value is read only with `reveal()` / `revealBytes()` at the call that hands it to a library (JDBC driver, HTTP header, HMAC), and the copy is cleared with `SecretValue.clear`. A `SecretValue` that is only an intermediate (read from a file or a request to be handed on) is destroyed with `destroy()` (`javax.security.auth.Destroyable`) by the code that is done with it: its own bytes are overwritten and it can no longer be read. This overwrites and does not guard: a copy a library made, a value nobody destroyed, a swapped page and a memory dump are not defended against. A secret kept in a file is read only through the `PrivateStorage` port (`readSecret`), which the edition implements. |
| CLI results | The JSON result that the user receives is not a log, so it is collected through one injected output bean (stage 5). |

## Related Classes and Settings

| Role | Location |
| --- | --- |
| Logger usage examples | `SessionBoardService` (`sennard-server/.../board/SessionBoardService.java:88-89,120-121`), `ToolEntrance` (`.../entrance/ToolEntrance.java:130,133`) |
| Level, file, rotation | `logging.level.root: WARN` in `sennard-personal/src/main/resources/application.yaml` (`:61`), and `logging.file.name` and `logging.logback.rollingpolicy.*` supplied by `SennardPersonalApplication` (`SennardPersonalApplication.java:32-36`) |
| Rule checks | `e-console-output`, `e-print-stack-trace`, and `f-swallowed-catch` of `ArchitectureRulesTest` (`sennard-arch-checks/src/test/resources/ceilings.properties`) |

## How It Works

### The Form of Logging an Exception

```java
// SessionBoardService.java:88-89
} catch (UnavailableException failed) {
    LOG.warn("The board rows could not be read; the list shows presence only.", failed);
```

The request continues after the handling, so the exception is not swallowed: it is logged and a reduced answer is returned. When it cannot be handled, it is rethrown with the cause attached (`SessionBoardService.java:67-68`).

```java
// ToolEntrance.java:130
LOG.warn("TOOL_CALL_FAILED {}", name, failure);
```

When the tool entrance receives an unknown exception, it passes only the name as an argument and the throwable as the last argument. The answer sent to the caller contains only a fixed code, not the original message (`UnavailableCode.STORE_UNAVAILABLE`).

### Log Configuration

For the personal server, `SennardPersonalApplication` supplies the file location (`${sennard.state-dir}/logs/sennard-server.log`) and the rotation policy as default properties. The active log rolls over daily or at the 10 MB size threshold. The archive policy retains up to 7 daily periods and caps archived logs at 100 MB in total. A day can have multiple archives, and the total excludes the active file. This is so that the trace of a startup failure can be read even in an environment without a console (a logon task). The user changes them with `logging.*` in `sennard.yaml`. The level is not changed in code.

### Request Identifier (Convention, to Be Implemented in Stage 5)

A single servlet `Filter` bean puts the per-request identifier into the MDC. The log pattern prints the MDC key through configuration. A Filter created with `new` is not used. There is no implementation at present.

## Notes

- Each class has a `private static final Logger` (the build rule `d-stateful-static` treats it as an immutable constant).
- When migrating an area, remove `System.out`, `printStackTrace`, and swallowing `catch` blocks together. The rule reports that the ceiling is to be lowered by the amount the count has decreased, until the ceiling reaches 0.
- When diagnostic output is needed in a test, use a logger or an assertion message.
- Do not pass large bodies or secret values as log arguments. Do not put them in exception messages either.

## Usage Examples

- Log and reduced answer: `SessionBoardService.java:84-90`.
- Rethrow with the cause attached: `SessionBoardService.java:67-68`, `:77-79`.
- An unexpected exception at the entrance: `ToolEntrance.java:128-135`.

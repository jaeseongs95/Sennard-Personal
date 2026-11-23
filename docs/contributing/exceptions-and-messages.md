[한국어](exceptions-and-messages.ko.md)

# Exceptions and Messages

This document defines the error conventions for Sennard. Business code throws only a code and arguments, and a boundary produces the sentence in one place from the message catalog. The reference implementations are the status board and session messaging.

## Starting Points

- Problem: in the earlier code, errors existed in several unrelated sets, such as `ToolFailure`, `ContractError`, `StoreException`, and `ServiceFailure`, and there were also three message-text classes (`Messages`). It was not possible to see in one place which exception becomes which response.
- Choice: one root (`sealed SennardException`) with seven categories, a code enum per category, and a single Spring `MessageSource` for sentences. The conversion of an MCP result is one exhaustive `switch`, and HTTP is handled in one `@RestControllerAdvice`. Common handling is placed in AOP (Spring's declarative services).

## Categories and Codes

| Category (`Category`) | Exception class | Code enum | Wire `error.code` | Meaning |
| --- | --- | --- | --- | --- |
| `INPUT` | `InvalidInputException` | `InputCode` | `INVALID_INPUT` | The caller's input is wrong. It can be fixed. |
| `NOT_FOUND` | `NotFoundException` | `NotFoundCode` | `NOT_FOUND` | What the call refers to does not exist. |
| `CONFLICT` | `ConflictException` | `ConflictCode` | `CONFLICT` | It collided with another state or another write. Reading again or retrying resolves it. |
| `GATE` | `GateRefusedException` | `GateCode` | `GATE_FAILED` | A product rule refuses this step now. |
| `BINDING` | `BindingException` | `BindingCode` | `BINDING_INVALID` | It is not bound to the required session or caller, or the binding is invalid. |
| `INTEGRITY` | `IntegrityException` | `IntegrityCode` | `INTEGRITY_FAILED` | The data is inconsistent with itself or with a constraint of the store. |
| `UNAVAILABLE` | `UnavailableException` | `UnavailableCode` | `MCP_UNAVAILABLE` | The server cannot do it now (store outage, limit). It is not the caller's fault. |

On the wire, the reason within a category is `error.details.reasonCode` (the constant name of the code enum). Each code has a catalog key (`messageKey`). Code names do not overlap among the seven enums, and a test guards this (`ErrorMessagesTest.java:145`).

## Related Classes

| Role | Class |
| --- | --- |
| Root and category | `SennardException` (`sennard-domain/.../domain/error/SennardException.java:16-17`), `Category` (`.../Category.java:10-22`), `ErrorCode` (sealed interface) |
| Subordinate exceptions and codes | Seven pairs such as `InvalidInputException` and `InputCode` (same folder) |
| Producing sentences | `CatalogMessageSource` (`sennard-app/.../message/CatalogMessageSource.java`), `CatalogPattern` (`.../message/CatalogPattern.java`), `SennardMessageResolvable` (`.../message/SennardMessageResolvable.java`) |
| Error translation | `PersistenceErrors` (`sennard-app/.../support/PersistenceErrors.java:45-53`), `ErrorTranslationAdvisor` (`.../ErrorTranslationAdvisor.java:21-24`) |
| MCP result conversion | `SennardToolResults` (`sennard-server/.../entrance/SennardToolResults.java:46-54`): the one place that builds the `CallToolResult` of a tool, so every tool answers with the envelope `{schemaVersion, ok, data, error}`. The one declared exception is `codex_hook`, whose result Codex's hook runner reads as the hook's own JSON; `ToolEnvelopeServerTest` holds every other tool of `tools/list` to the envelope |
| HTTP result conversion | `SennardExceptionHandler` (`sennard-server/.../entrance/SennardExceptionHandler.java`) |
| Catalog | `locales/en/*.json`, `locales/ko/*.json` (for example `error.json`, `services.json`) |

## How It Works

### The Throwing Side

Business code does not write sentences. It supplies only a code and the arguments (named values) that the sentence will use.

```java
// sennard-domain: BoardRules.java
throw new InvalidInputException(InputCode.SUMMARY_INVALID, SennardException.args("maxLength", maxLength));
```

When more detail must be carried on the wire, as with a capacity limit, `details` is supplied (the constructor of `UnavailableException`). The exception message (`getMessage()`) is only the code name. This is so that values such as a body or a path do not leak into log lines.

A sentence that a model or a user reads comes from the catalog by a `messageKey` (English and Korean), and is never written in the code: a refusal that has something to say about the input (a time range that is backwards, skill ids that are not installed) is a code of its own with its sentence in `locales/` that names what was sent through the arguments. The one text that is not from the catalog is what a JSON Schema validator says in `details.validationErrors`; it is always English (`SchemaLocale.IN_ENGLISH`, used by every validation call), whatever the default language of the JVM, so that one server does not answer some refusals in English and others in the language of the machine.

### `sennard-domain` Does Not Know Spring

For this reason an exception cannot implement Spring's `MessageSourceResolvable` directly, and it offers the same shape instead (`messageKey()` and `arguments()`). `SennardMessageResolvable` in `sennard-app` wraps this as a `MessageSourceResolvable`. The build enforces the rule: if `sennard-domain` source imports `org.springframework`, `java.sql`, or `tools.jackson.databind`, `o-domain-imports` fails.

### Producing Sentences

`CatalogMessageSource` is a Spring `MessageSource`, and a sentence is produced by calling this single bean through a `MessageSourceAccessor`. The catalog is `locales/<language>/*.json` (one `java.text.MessageFormat` pattern per key), and arguments are written by name. A phrase that varies with a value is written with the key `key#argument=value` (chapter 2 of `docs/contributing/i18n.md`). Passing one `Map` as `Object[]{map}` makes them named arguments. If a key is absent in a language, English is looked up. The record of the translation (`ko`) is `locales/ko/translations.lock.json`, and it is updated with `:sennard-repo-tools:acceptTranslation -Ptool="ko"`.

When a new code is created and its catalog key is absent, a test fails (`ErrorMessagesTest.java:73`, for both English and Korean). It also checks that the same code is rendered in both languages and that the arguments are filled in (`:92`).

### Translation: Data Access Exceptions Are Converted in One Place

An advice around service methods converts Spring and Jakarta Validation exceptions into an `SennardException`. A service does not catch a `DataAccessException` and recreate its own exception.

| Incoming exception | Outgoing exception |
| --- | --- |
| `ConstraintViolationException` | `InvalidInputException(VALIDATION_FAILED)`, `details.violations` |
| `DuplicateKeyException` | `ConflictException(DUPLICATE)` |
| `ConcurrencyFailureException` (serialization failure, deadlock, optimistic locking) | `ConflictException(CONCURRENT_UPDATE)` |
| `DataIntegrityViolationException` | `IntegrityException(REFERENCE_VIOLATION or VALUE_NOT_ALLOWED)` |
| Any other `DataAccessException` | `UnavailableException(STORE_UNAVAILABLE)` |

`ErrorTranslationAdvisor` runs outside the transaction and translates commit failures too. A database concurrency failure is translated to `CONCURRENT_UPDATE` in `PersistenceErrors`. The product reports the conflict without rerunning the transaction. To distinguish a foreign key violation from a CHECK violation, only the ISO standard SQLSTATE 23503 is read (`isForeignKey` in `PersistenceErrors.java`). There is no hand-built SQLSTATE table. The earlier `StoreException` (`sennard-api`) thrown at commit is read by its code, and when the earlier store disappears, this branch disappears as well.

### MCP Result Conversion: One Exhaustive `switch`

```java
// SennardToolResults.java:46-54
return result(ApiResult.failed(switch (failure) {
    case InvalidInputException input -> body(input);
    case NotFoundException missing -> body(missing);
    ...
    case UnavailableException unavailable -> body(unavailable);
}));
```

There is no `default`. When a category is added, this `switch` does not compile, so every boundary must decide its answer. Sentences are fixed to the English that a model reads (`contentLanguage` in `_meta` is `en`). Korean is the responsibility of the HTTP boundary, which is read by people.

### HTTP

The HTTP boundary converts an `SennardException` into an RFC 9457 `ProblemDetail` (`application/problem+json`) in one `@RestControllerAdvice` (`SennardExceptionHandler`, `sennard-server/.../entrance/SennardExceptionHandler.java`). The status is decided by an exhaustive `switch` per category: `INPUT` 400, `NOT_FOUND` 404, `CONFLICT` 409, `GATE` 422, `BINDING` 401, `INTEGRITY` 422, `UNAVAILABLE` 503. The sentence is resolved from the `MessageSource` by `Accept-Language`, and when the header is absent it is `spring.web.locale: en`. `code`, `reasonCode`, `messageKey`, and `details` are properties of the problem. `SennardExceptionHandlerTest` verifies the response for each category and the English and Korean sentences on a real Tomcat. A `DataAccessException` that the store raises outside a service method and a `SettingsException` of the settings store go through the same translation as in the application layer (`PersistenceErrors.ofDataAccess` and `ofSettings`) and are answered the same way: a race is 409 `CONFLICT` (`CONCURRENT_UPDATE`), a constraint is 422, and only a store that cannot answer is 503 (`MCP_UNAVAILABLE`, `reasonCode: STORE_UNAVAILABLE`). Any other exception that is not an `SennardException` is not taken by this handler. The HTTP entrances of earlier areas (the admin screen controllers) use this handler when they are migrated.

## Cautions

- Do not write a sentence in business code. If a key is absent from the catalog, a test fails.
- A code that changes the wire (category, reason) is a public contract. When it changes, `contracts/api-result.v1.schema.json`, the goldens, and the English and Korean documents are updated in the same change. Example: for an unknown target in messaging, `MCP_UNAVAILABLE` with `details.reason` was changed to `NOT_FOUND` with `reasonCode=SESSION_NOT_FOUND`.
- Do not swallow an exception with a broad `catch`. When one is caught, a log is written or the cause (`cause`) is attached and the exception is thrown again (`SessionBoardService.java:67-68`, `:88-89`).
- Message wrapping for a single call (for example the `send` instruction not to resend and to compare the receipts) is expressed as a code (`UnavailableCode.SEND_REJECTED`). Strings are not concatenated.
- The session registry and the binding of a call to its session raise `SennardException`s like every other area: `SessionBinding.require()` throws `BindingException(BINDING_REQUIRED)`, and the registry's refusals (`CURRENT_GENERATION_PROTECTED`, `GENERATION_CHANGED`, `GENERATION_STALE`, `STORE_CONFLICT`, `SESSION_NOT_FOUND`) are `ConflictException`s and a `NotFoundException`. `ServiceFailure` no longer exists, so a tool entrance has one failure model to answer.

## Usage Examples

- Throwing: `BoardRules.java` (`SUMMARY_INVALID`) and the capacity refusal in `MessagingService.java` (`UnavailableCode.MESSAGE_*_FULL` with `details`).
- Changing the category in an adapter: `SessionBoardService.java:67-68` (`STORE_UNAVAILABLE` to `BOARD_NOT_RECORDED`).
- Result conversion test: `ToolEntranceTest.java:185` (a rule refusal and a real DB constraint are translated at the entrance).
- Catalog test: `ErrorMessagesTest.java:73,92,108,145`.

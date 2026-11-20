[한국어](validation.ko.md)

# Validation

This document defines the conventions for validating input and configuration. A rule is written in one place as a constraint declaration of the Jakarta Validation 3.1 specification, and Spring runs the check.

## Starting Points

- Input validation is done through the Jakarta Validation specification rather than by building by hand what that specification already covers. Differences in what is required per tool are handled by groups, the check of method input by method validation, nested objects and collections by `@Valid` and container element constraints, special rules by `ConstraintValidator`, and messages by interpolation and `MessageSource` (Jakarta Validation 3.1 specification 3.1.1.2, 2.4.3 and 5.6, 3.4, 6.3, and 11.3.4 record support).
- Therefore hand-written validation code (string regular expressions and `if` chains) is not newly written, and constraints are declared on input records and on the parameters of service methods. The implementation is Hibernate Validator, which Boot brings in (`spring-boot-starter-validation`).

## What Is Checked Where

| Location | Check | On failure |
| --- | --- | --- |
| Structure, required fields, length, and pattern of the public contract | `ContractGuard` publishes and checks `contracts/<tool>-request.v1.schema.json` | `INVALID_INPUT` (`workflow.schema.contractMismatch`, `details.validationErrors`) |
| The declared constraints of a tool input record | `ToolEntrance` creates the input record and then calls `Validator.validate` | `ConstraintViolationException` to `VALIDATION_FAILED` |
| Service method parameters | Method validation of a `@Validated` service (`@NotBlank` and similar) | The same translation |
| Business rules (one line, length, byte count, range) | The pure rules of `sennard-domain` (`BoardRules`, `MessageRules`) throw with a code and arguments | The `InputCode` of the rule |
| Configuration values | The constraints of a `@ConfigurationProperties` record, at startup | Startup failure (explained by Boot's `FailureAnalyzer`) |

Rejecting an unknown argument is not validation but the responsibility of the Jackson deserialization setting (`FAIL_ON_UNKNOWN_PROPERTIES`) (`ToolEntrance.java:87`).

## Related Classes

| Role | Class |
| --- | --- |
| Tool input record and constraints | `SessionBoardTools.UpdateInput` (`sennard-server/.../board/SessionBoardTools.java:24`), `PrepareInput` and `AcknowledgeInput` of `SessionMessageTools` (`.../messaging/SessionMessageTools.java:28-45`) |
| Running input validation | `ToolEntrance.input` (`sennard-server/.../entrance/ToolEntrance.java:143-154`) |
| Service method validation | `BoardService` (`@Validated`, `.../board/BoardService.java:30,49,59`), `MessagingService.java:134,168` |
| Constraint translation | `PersistenceErrors.java:48` (`ConstraintViolationException` to `VALIDATION_FAILED`) |
| Business rules | `BoardRules` (`sennard-domain/.../board/BoardRules.java`), `MessageRules` (`sennard-domain/.../messaging/MessageRules.java`) |
| Configuration constraints | `BoardProperties.java:20-23`, `MessagingProperties.java:23-24` |

## How It Works

### Input Record

```java
// SessionBoardTools.java:24
public record UpdateInput(String schemaVersion, @NotBlank String summary, String role) { }
```

`ToolEntrance` converts the flat argument map into this record with Jackson (`convertValue`) and checks the declared constraints with `Validator.validate`. If there is a violation, it throws a `ConstraintViolationException`, and the same translation as the service advice converts it to `InvalidInputException(VALIDATION_FAILED)` (the field and the message in `details.violations`). Nested records and lists use `@Valid` and element constraints.

```java
// SessionMessageTools.java:39
public record AcknowledgeInput(String schemaVersion, @NotNull List<@NotNull String> messageIds, List<@NotNull @Valid Receipt> receipts) { }
```

### Service Methods

When `@Validated` is placed on a service class, the proxy checks parameter constraints such as `@NotBlank String host`. Callers that invoke the service without going through an input record (hook handlers and the like) are also made to follow the same rules.

### Business Rules Are Pure Functions of the Domain

Whether a value is a single line, how many characters it has, and how many UTF-8 bytes it has are business rules, so they are placed in `sennard-domain`, and values such as the length are received as arguments (they come from configuration).

```java
// BoardService.java:59-61
String line = BoardRules.summary(summary, properties.summaryMaxLength());
```

What can be expressed as a declared constraint (required, size, pattern) is declared with a constraint annotation, and only what cannot be expressed with an annotation (a length that depends on a configuration value, a byte count, a rule that depends on state) is written as a domain function. The same rule is not written in two places.

### Groups and Metadata (Stage 5 Responsibility)

When what is required differs per tool, it is separated with `groups` and `@GroupSequence` and passed to nested objects with `@ConvertGroup`. The way to keep the public JSON schema (an external specification whose bytes are fixed by the goldens) and the declared constraints from diverging is to compare in a test the result produced by the metadata API of Jakarta Validation (specification chapter 7, `Validator.getConstraintsForClass`) with the schema. Currently, in the two areas, the contract file is the source of truth for the schema and the constraints of a record are a subset of it, so a test cannot yet catch a divergence. The owner migrating an area adds one such comparison test per tool.

### Configuration Values

```java
// BoardProperties.java:20
@DefaultValue("200") @Min(1) @Max(200) int summaryMaxLength,
```

A value fixed in a public contract is prevented from being raised with `@Max`. If a value longer than the contract were allowed through configuration, this check would pass and the contract would reject it. A test verifies that the default equals the contract (`MessagingPropertiesTest`).

## Cautions

- A validation message is resolved with `MessageSource`. The wire is not the default text of a constraint but the Sennard code (`VALIDATION_FAILED`) and `details.violations`.
- `@Validated` works through a proxy, so it does not apply to a self-invocation within the same class.
- The component names of an input record are the same as the wire keys. When the names differ, they are matched with `@JsonProperty` and `@JsonAlias` only at the boundary (the inside of the domain uses the new name).
- If a record of `sennard-domain` needs constraint annotations, `jakarta.validation-api` (a dependency that contains only annotations) can be added, but at present `sennard-domain` has no dependencies.

## Usage Examples

- Constraint declaration and check: `ToolEntranceTest.java:165` (`aSummaryOfOnlyBlanksIsStoppedByTheRecordsDeclaredConstraint`).
- Service method validation: `AbstractBoardServiceTest.java:257` (`invalidInputIsRefusedWithItsCodeAndStoresNothing`, an empty host to `VALIDATION_FAILED`).
- A case that the contract blocks first: `ToolEntranceTest.java:173` (a two-line summary is rejected first by the schema `pattern` as `INVALID_INPUT`).

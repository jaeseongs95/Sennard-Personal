[한국어](tool-input-mapping.ko.md)

# Tool Entrance Mapping

This document describes the path by which the input of an MCP tool travels from the flat keys of the public contract to an input record and a service call, and the rationale for choosing that method.

## The Method Decided by Real Calls (Confirmed in Stage 4)

Problem: it was not known whether placing a record on a parameter of a Spring AI `@McpTool` makes the tool input arrive as flat keys or wrapped as `{"input":{...}}`. The public contract (`contracts/*-request.v1.schema.json`) uses flat keys.

Measurement (the Spring AI starter of the current BOM version, a real Tomcat, called with the MCP client of the SDK: `McpToolInputShapeTest`):

| Declaration method | Published schema | Flat keys `{"summary":"s"}` | Wrapped keys `{"input":{...}}` |
| --- | --- | --- | --- |
| A single `@McpToolParam` record parameter | One property (`input`) containing the record components | Rejected: `INVALID_INPUT`, `UNDECLARED_ARGUMENT` | Arrives as the record |
| `@McpToolParam` flat parameters | Flat | Arrives (the method creates the record) | Not applicable |
| A `SyncToolSpecification` bean with Jackson `convertValue` | Flat (the schema is supplied directly) | Arrives (`convertValue` creates the record) | Rejected |

Choice: (b), a `SyncToolSpecification` bean with `convertValue`. The reasons are as follows.

1. The public contract uses flat keys, and a JSON Schema file is already the source of truth. With (a), flat parameters restate the same information (name, required, description) in parameter annotations, and a post-processing step that replaces the published schema with the contract file is needed anyway. With two sources, they diverge.
2. A single input record becomes the input shape of the tool. A record is not assembled by hand by repeating a parameter list.
3. Input validation (Jakarta Validation), error translation, and session binding can be placed in one place (`ToolEntrance`).
4. Replacing the published schema happens in `ContractGuard` (or one spec bean), and there is no reflection table.

Constraint discovered: the starter collects beans of type `List<SyncToolSpecification>`. If only a single spec is placed as a bean, it is not published (`Beans` in `McpToolInputShapeTest`). The MCP transport is fixed to STATELESS (`spring.ai.mcp.server.protocol` in `application.yaml`; any other value prevents the server from starting: `StatelessMcpProtocol`).

## Related Classes

| Role | Class |
| --- | --- |
| Entrance | `ToolEntrance` (`sennard-server/.../entrance/ToolEntrance.java`) |
| Result conversion | `SennardToolResults` (`.../entrance/SennardToolResults.java:46-54`) |
| Schema publication and enforcement | `ContractGuard` (`OWN_TOOLS` in `sennard-server/.../tools/ContractGuard.java`) |
| Tool declaration | `SessionBoardTools` (`.../board/SessionBoardTools.java:24-45`), `SessionMessageTools` (`.../messaging/SessionMessageTools.java:28-58`) |
| Tests | `McpToolInputShapeTest`, `ToolEntranceTest` (`sennard-personal/src/test/.../serverit/`) |

## How It Works

```java
// SessionBoardTools.java:30-36
@Bean
List<SyncToolSpecification> sessionBoardTools(ToolEntrance entrance, SessionBoardService service) {
    return List.of(
        entrance.tool("update_session_status", UpdateInput.class, hints(false),
            (call, input) -> service.update(call.session().host(), ToolSupport.sessionId(call.session()), input.summary(), input.role())),
        ...
```

In one call, `ToolEntrance` does the following in this order.

1. It determines the session (`McpSessionResolver`, including the `sessionRef` argument).
2. It converts the arguments (the flat map without `sessionRef`) into the input record with Jackson. An unknown argument is a failure.
3. It checks the declared constraints of the input record ([Validation](validation.md)).
4. The result of the handler is answered as `ApiResult.ok`, an `SennardException` through the exhaustive `switch` of `SennardToolResults`, and an exception of an earlier area in the earlier way.

The input schema (what the model reads) is the contract file plus `sessionRef`. `ContractGuard` publishes the schema of the tools in `OWN_TOOLS` and enforces the arguments, and the schema that the entrance supplies is a placeholder that this guard replaces. Therefore every tool declared through the entrance must be in `OWN_TOOLS`, and a test verifies this (`ToolEntranceTest.java:235`, `everyToolDeclaredWithTheEntranceIsPublishedByItsContractFile`).

## Order for Migrating a New Tool

1. Check whether `contracts/<tool>-request.v1.schema.json` exists, and create it if not (the source of truth).
2. Create the input record and declare the constraints. Component name = wire key.
3. Create the service method (`sennard-app`) ([DB and Transactions](db-and-transactions.md)).
4. Add a `List<SyncToolSpecification>` bean to a `@Configuration` class (`entrance.tool(...)`).
5. Add the tool name to `ContractGuard.OWN_TOOLS`.
6. Tests: a flat-key call with a real MCP client, a contract violation, a service rule refusal, and translation of a real DB constraint (the `ToolEntranceTest` pattern). When the published schema changes, update the golden (`L-tools_list.json`) and state the reason.

## Cautions

- Do not place `sessionRef` among the components of an input record. The entrance detaches it first and uses it for session binding.
- If an optional argument must be "absent" and the contract does not permit `null`, `null` of the corresponding component of the record means "absent" (`UpdateInput.role`).
- A change in a tool name, a key, an envelope, or an error code is a public contract change. `contracts/*.schema.json`, the goldens, and the English and Korean documents are updated in the same change. By moving to the entrance, the defaults of the `title` (an empty string) and the `annotations` of two tools changed in the golden (`L-tools_list.json`).
- The `@McpTool` annotation method still remains in tools that have not been migrated. When migrating, change them in the order above.

## Usage Examples

- Flat-key call test: `ToolEntranceTest.java:115` (`theBoardToolsTakeAndAnswerByTheContractsFlatKeys`).
- The test that is the basis for comparing the methods: `McpToolInputShapeTest.java` (four cases).
- A messaging tool with many arguments and a nested record: `SessionMessageTools.java:28-58`.

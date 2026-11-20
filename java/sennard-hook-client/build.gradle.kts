// The hook client and the MCP relay: the programs a host starts. The hook client is started for every command hook (Claude Code, Gemini CLI and Antigravity: every hook; Codex: its
// session start and end; its other hooks are calls of the MCP server, which is the relay). A host's own HTTP hook or HTTP MCP server cannot be used because the server's port is the
// operating system's choice at each start, so no definition can name it; these programs read it from the file where the server announces it. The relay is the host's stdio MCP
// server for the whole session. This module also holds the contract the server shares with them (HookClientCall, HookEndpoints, HookHeaders, ServerAddress). The host pays a
// program's start for every hook, so the module has no dependency on purpose: the JDK's java.base is all it loads. No Spring, no Jackson, no SLF4J. HookClientProcessTest fails
// when the client's or the relay's own JVM loads a class of one of them.
plugins {
    `java-library`
}

tasks.named<Test>("test") {
    // HookClientProcessTest starts the client in a JVM of its own on this module's classes and resources alone, as a host does with the jar: nothing else is on its class path.
    systemProperty("sennard.hookclient.classpath", sourceSets["main"].output.asPath)
}

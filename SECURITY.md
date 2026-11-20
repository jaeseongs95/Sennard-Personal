[한국어](SECURITY.ko.md)
# Security Policy

Because this plugin can handle audit flows for high-risk changes, do not post secrets, personal information, or reproducible attack procedures in public issues or pull requests.

If you find a vulnerability or a possible permission bypass, report it through [GitHub private vulnerability reporting](https://github.com/jaeseongs95/Sennard-Personal/security/advisories/new), and provide only the minimum necessary information on the affected versions, reproduction conditions, and observed results. Whether to disclose and the fix schedule are decided by the owner after confirming the scope of impact and the feasibility of a fix.

The receiving side first checks the scope of exposure, exploitability, workarounds, affected releases, and verification of the fix. After a fix, the related skills and MCP integration paths are verified again.

## Trust Boundaries

### Personal Edition Local Server (1.0.0 and Later)

The Sennard server is an HTTP server that runs on one PC for one user. The access controls that the server actually applies are as follows.

- **Listening address.** Connections are accepted only on `127.0.0.1`. There are two ports: one for MCP, hooks, and Actuator, and one for the admin page. Neither is fixed: the operating system assigns a free port to each at every server start, so another program that holds a port cannot block the server. The server announces its address in `server.properties` in the state folder (the port, the process id, and the process start time); the hook client and the MCP relay read it at each call, and treat the file as stale unless the process it names is alive with that start time. A second server on the same state folder does not start (`SERVER_ALREADY_RUNNING`). The current addresses are shown by `sennard status` (`port`, `adminPage`) and in the messages that ask the user to decide something.
- **Request headers.** On all paths, a request is rejected with 421 unless `Host` is `127.0.0.1` or `localhost` combined with the port that received the request. A request with an `Origin` header is rejected with 403 if the origin is not in the allow list. The allow list of the personal edition is empty, and the admin port allows only the origin of the admin page itself, `http://localhost:<admin page port>`.
- **OS user.** The server does not check the OS user of the caller. Verifying the calling OS user is outside the scope of Sennard, and the only control over who can connect is the binding of the server to `127.0.0.1`, which limits connections to programs on the same PC. Other OS users on the same PC are not excluded: the personal edition trusts every program that runs on this PC.
- **Body.** Only UTF-8 JSON bodies are accepted, and a body larger than the limit of its port is rejected with 413: 16 MiB on the MCP, hook, and Actuator port, and 64 KiB on the admin page port, which receives only small signed records.
- **No tokens are used.** The server has no token or password authentication. Any program running on this PC can call the server, including a program of another OS account, and Sennard treats all of them as a single trust scope. As a result, such a program can also have the server run a command with the `run` command of the `test-engineering` check, and that command runs with the privileges of the OS user who started the server.
- **Admin page.** Setting changes made on the admin page, such as saving instructions and approving roots, are applied only after the user's passkey signature is verified. The `Origin` and `Host` checks on the admin port are for rejecting requests from other web pages. The passkey signature does not stop every action of a program running as the same OS user: such a program can reject pending requests without a signature, register the first passkey while none is registered, run `sennard admin-reset --yes` and then register its own key, or change the installation files, the store, or the server's environment directly. These cases, and what the page shows of them, are listed in [What Is Not Prevented](docs/user/admin-page.md#what-is-not-prevented).
- **Server log.** The server writes logs at warning level and above to `<state folder>/logs/sennard-server.log` (default `~/.sennard/logs/sennard-server.log`). Request bodies are not written to the log.

### Trust in Execution Records

The local MCP checks the execution order, revision, plan signature, required artifact IDs, and audit `PASS` declarations. The `verified` value, locators, and worker identifiers in evidence submitted by a caller are trusted inputs, not authenticated facts. The server does not independently confirm file contents or the actual identity of an agent. Therefore, in an environment where a hostile client can submit arbitrary results, this plugin alone cannot ensure audit integrity. Such an environment needs a separately connected authenticated actor identity and content-addressed evidence store.

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.hookclient.CallerTimeZone;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * The running server, over its own HTTP endpoints: Boot's shutdown and health, and the store status. The server's port is the operating system's choice at each start, so the server is
 * found where it says it is: the address file it writes in the state folder ({@link ServerAddress}), read again for every request (a server that was stopped and started has another port),
 * and trusted only while its process lives. Nothing here knows a port beforehand.
 */
public final class ServerControl {
    private static final Logger LOG = LoggerFactory.getLogger(ServerControl.class);
    static final Duration POLL = Duration.ofMillis(200);
    /** What a command waits for one answer of a server that accepted the connection: a process of this PC on its loopback address answers at once or is stuck. */
    private static final Duration ANSWER = Duration.ofSeconds(30);
    /** How long a stopped server's process is given to end: the shutdown is graceful, and the settings database is closed by then. */
    private static final Duration EXIT = Duration.ofSeconds(30);

    private final Path addressFile;
    private final RestClient client;

    /** Every request carries the caller's time zone when the environment names one (see {@link CallerTimeZone}). */
    public ServerControl(Path addressFile, Map<String, String> environment) {
        this(addressFile, environment, ANSWER);
    }

    /**
     * @param addressFile the file the server writes its address to ({@link InstalledSettings#serverAddressFile()})
     * @param answer how long one request waits for the answer: the readiness checks of a session start are polled inside a hook's time limit, so one that
     *        accepts the connection and never answers must not use it up
     */
    public ServerControl(Path addressFile, Map<String, String> environment, Duration answer) {
        this.addressFile = addressFile;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(answer);
        RestClient.Builder builder = RestClient.builder().requestFactory(requests);
        CallerTimeZone.from(environment).ifPresent(zone -> builder.defaultHeader(HookHeaders.TIME_ZONE, zone));
        this.client = builder.build();
    }

    /** The file the address is read from. */
    public Path addressFile() {
        return addressFile;
    }

    /** The address of the server that runs now; empty when the file is missing, broken, or names a process that is gone or was started at another time (a leftover of a server that was killed). */
    public Optional<ServerAddress> running() {
        return ServerAddress.read(addressFile).filter(ServerAddress::live);
    }

    /** Whether a server of this state folder runs. */
    public boolean listening() {
        return running().isPresent();
    }

    public boolean healthy() {
        Optional<ServerAddress> address = running();
        if (address.isEmpty()) {
            return false;
        }
        try {
            return client.get().uri(endpoint(address.get(), "/actuator/health")).retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (RestClientException notUp) {
            // Boundary (external process): a probe of the server's health, whose failure to answer (a refused connection, a timeout, a non-2xx status) is the answer "not healthy".
            LOG.debug("The server at {} did not answer its health check as up.", address.get().baseUrl(), notUp);
            return false;
        }
    }

    /**
     * The store's preparation state from the server's own endpoint; empty when the server does not answer. Empty has this one meaning: callers that must tell a server that
     * is down from one that answers ask {@link #healthy()} or {@link #listening()}.
     */
    public Optional<StoreStatus> storeStatus() {
        Optional<ServerAddress> address = running();
        if (address.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(client.get().uri(endpoint(address.get(), "/actuator/sennard")).retrieve().body(StoreStatus.class));
        } catch (RestClientException notAnswering) {
            // Boundary (external process): a probe of the server's store status; not answering is the answer "unknown", never an error of the caller.
            LOG.debug("The server at {} did not report its store status.", address.get().baseUrl(), notAnswering);
            return Optional.empty();
        }
    }

    /** Where the server's admin page listens, from the server's own endpoint; empty when the server does not answer (the page's port is chosen at each start). */
    public Optional<String> adminPage() {
        Optional<ServerAddress> address = running();
        if (address.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(client.get().uri(endpoint(address.get(), "/actuator/sennard")).retrieve().body(AdminPageStatus.class)).map(AdminPageStatus::adminPage);
        } catch (RestClientException notAnswering) {
            // Boundary (external process): a probe of the server's endpoint; not answering is the answer "unknown", never an error of the caller.
            LOG.debug("The server at {} did not report where its admin page listens.", address.get().baseUrl(), notAnswering);
            return Optional.empty();
        }
    }

    public void retryPreparation() {
        ServerAddress address = running().orElseThrow(() -> new UnavailableException(UnavailableCode.SERVER_UNREACHABLE, SennardException.args("where", "no live server is announced in " + addressFile)));
        try {
            client.post().uri(endpoint(address, "/actuator/sennard")).retrieve().toBodilessEntity();
        } catch (RestClientException cause) {
            throw new UnavailableException(UnavailableCode.SERVER_UNREACHABLE, SennardException.args("where", "no live server is announced in " + addressFile), cause);
        }
    }

    /** Asks the server to end and waits until its process is gone. A server that is already down is not an error. */
    public void shutdown() {
        Optional<ServerAddress> address = running();
        if (address.isEmpty()) {
            return;
        }
        try {
            client.post().uri(endpoint(address.get(), "/actuator/shutdown")).retrieve().toBodilessEntity();
        } catch (RestClientException cause) {
            throw new UnavailableException(UnavailableCode.STOP_FAILED, SennardException.args("what", "shutdownRefused", "file", addressFile), cause);
        }
        awaitExit(address.get().pid());
    }

    /**
     * Waits for a process, of this machine and not necessarily a child of this one, to end ({@link ProcessHandle#onExit()}): the port is released before the server's beans are closed and
     * before the operating system lets go of its files, so the process's end is the only sign that the settings database and the jars are free.
     *
     * @throws UnavailableException STOP_FAILED when the process is still there after {@link #EXIT}
     */
    public static void awaitExit(long pid) {
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty()) {
            return;
        }
        try {
            process.get().onExit().get(EXIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new UnavailableException(UnavailableCode.STOP_FAILED, SennardException.args("what", "processHeld", "pid", String.valueOf(pid)), late);
        } catch (ExecutionException failed) {
            throw new UnavailableException(UnavailableCode.STOP_FAILED, SennardException.args("what", "processHeld", "pid", String.valueOf(pid)), failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException(UnavailableCode.COMMAND_INTERRUPTED, SennardException.args("command", "stop"), interrupted);
        }
    }

    public void awaitHealthy(Duration deadline) {
        await(this::healthy, deadline, UnavailableCode.START_FAILED, SennardException.args("what", "notHealthy", "file", addressFile));
    }

    private static URI endpoint(ServerAddress address, String path) {
        return address.baseUrl().resolve(path);
    }

    /** Waits for a condition another process changes; the deadline is only for a process that never gets there. */
    public static void await(BooleanSupplier condition, Duration deadline, UnavailableCode code, Map<String, Object> arguments) {
        long end = System.nanoTime() + deadline.toNanos(); // a wait for another process: the monotonic clock, not a date
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - end > 0) {
                throw new UnavailableException(code, arguments);
            }
            LockSupport.parkNanos(POLL.toNanos());
        }
    }
}

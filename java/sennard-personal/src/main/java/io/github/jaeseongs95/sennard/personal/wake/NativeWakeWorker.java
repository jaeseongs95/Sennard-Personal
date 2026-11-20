// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Capability;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchReply;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchResult;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeAdapter.ManagedConversation;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeAdapter.ManagedLauncher;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Runs under the accepted command hook's local environment without starting an application context. */
public final class NativeWakeWorker {
    private static final System.Logger LOG = System.getLogger(NativeWakeWorker.class.getName());
    private static final int MAX_OPEN_CHILDREN = 8;

    private NativeWakeWorker() {
    }

    private record Host(HostDefinition definition, NativeWakeProvider provider) {
    }

    private record Poll(String marker, String childId, Launch launch) {
    }

    public static int run(String[] args, InputStream input, Map<String, String> environment) {
        if (args.length != 3) {
            return 2;
        }
        Map<String, Host> hosts = ServiceLoader.load(NativeWakeProvider.class, NativeWakeProvider.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .map(provider -> new Host(provider.definition(), provider))
                .collect(Collectors.toUnmodifiableMap(host -> host.definition().host().label(), Function.identity()));
        Host host = hosts.get(args[1]);
        if (host == null) {
            return 2;
        }
        JsonMapper mapper = JsonMapper.builder().build();
        try {
            DataInputStream stdin = new DataInputStream(input);
            String key = stdin.readUTF();
            ServerAddress server = new ServerAddress(stdin.readInt(), stdin.readLong(), Instant.parse(stdin.readUTF()));
            byte[] body = stdin.readNBytes(NativeWakeProtocol.INPUT_LIMIT + 1);
            if (!NativeWakeProtocol.validKey(key) || body.length > NativeWakeProtocol.INPUT_LIMIT) {
                return 2;
            }
            HostEvent event = host.definition().hookAdapter(mapper).read(args[2], HookBody.posted(body));
            HostContext context = event.context();
            // The main server classifies the actor before it echoes this registration's key. The worker reads only the target and eligible boundary.
            if (!(event instanceof HostEvent.SessionStarted) && !(event instanceof HostEvent.TurnEnded ended && ended.continuable() && !ended.alreadyContinued())) {
                return 2;
            }
            var target = new WakeTransport.Target(context.sessionId(), context.workspace());
            NativeWakeAdapter adapter = host.provider().adapter(new LocalWakeProcess(), mapper);
            WakeTransport.Availability nativeWake = adapter.connect(environment, target);
            WakeTransport.Connection rootWake = nativeWake instanceof WakeTransport.Availability.Ready ready ? ready.connection() : null;
            var managed = adapter.managedLauncher(environment, target);
            if (managed.isPresent()) {
                return watch(server, key, context.sessionId(), rootWake, managed.orElseThrow(), mapper);
            }
            if (rootWake != null) {
                return watch(server, key, context.sessionId(), rootWake, null, mapper);
            }
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (IOException | IllegalArgumentException | DateTimeParseException | HookRejected | JacksonException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "Native wake worker stopped ({0})", unavailable.getClass().getSimpleName());
            return 1;
        }
    }

    static int watch(ServerAddress server, String key, String session, WakeTransport.Connection rootWake,
            ManagedLauncher managed, JsonMapper mapper) throws IOException, InterruptedException {
        Capability capability = managed == null ? Capability.ROOT : rootWake == null ? Capability.MANAGED : Capability.BOTH;
        ObjectReader launches = mapper.readerFor(Launch.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Map<String, ManagedConversation> children = new HashMap<>();
        try {
            while (server.live() && !Thread.currentThread().isInterrupted()) {
                Poll poll = poll(server, key, capability, launches);
                if (poll == null) {
                    return 0;
                }
                if (!server.live()) {
                    return 0;
                }
                if (poll.launch() != null && (managed == null || !createAndReport(server, key, session, poll.launch(), managed, children))) {
                    return 1;
                }
                if (poll.marker() != null && !wakeAndReport(server, key, session, poll, rootWake, children)) {
                    return 1;
                }
            }
            return 0;
        } finally {
            children.values().forEach(ManagedConversation::close);
            if (rootWake != null) {
                rootWake.close();
            }
        }
    }

    private static boolean wakeAndReport(ServerAddress server, String key, String session, Poll poll, WakeTransport.Connection rootWake,
            Map<String, ManagedConversation> children) throws IOException {
        String marker = poll.marker();
        if (!NativeWakeProtocol.validMarker(marker)) {
            return false;
        }
        String childId = poll.childId();
        var target = childId == null ? rootWake : children.get(childId);
        WakeTransport.Outcome attempted = target == null ? new WakeTransport.Outcome.NotSent("CLIENT_CONVERSATION_UNAVAILABLE")
                : target.wake(childId == null ? session : childId, marker);
        NativeWakeProtocol.Result outcome = switch (attempted) {
            case WakeTransport.Outcome.Submitted ignored -> NativeWakeProtocol.Result.SUBMITTED;
            case WakeTransport.Outcome.NotSent ignored -> NativeWakeProtocol.Result.NOT_SENT;
            case WakeTransport.Outcome.Unknown ignored -> NativeWakeProtocol.Result.UNKNOWN;
        };
        return complete(server, key, marker, childId, outcome);
    }

    private static boolean createAndReport(ServerAddress server, String key, String session, Launch launch, ManagedLauncher managed,
            Map<String, ManagedConversation> children) throws IOException, InterruptedException {
        ManagedConversation opened = null;
        boolean retained = false;
        try {
            LaunchReply result;
            try {
                children.values().removeIf(child -> {
                    if (child.live()) {
                        return false;
                    }
                    child.close();
                    return true;
                });
                if (children.size() >= MAX_OPEN_CHILDREN) {
                    result = new LaunchReply(LaunchResult.NOT_SENT, null);
                } else {
                    opened = managed.create(launch);
                    String childId = opened.threadId();
                    result = childId.equals(session) || children.containsKey(childId) ? new LaunchReply(LaunchResult.UNKNOWN, null)
                            : new LaunchReply(LaunchResult.CREATED, childId);
                }
            } catch (NativeWakeAdapter.UnsupportedSelection refused) {
                LOG.log(System.Logger.Level.DEBUG, "The host did not list the managed model and effort ({0})", refused.getClass().getSimpleName());
                result = new LaunchReply(LaunchResult.SELECTION_UNSUPPORTED, null);
            } catch (IOException | IllegalArgumentException unknown) {
                LOG.log(System.Logger.Level.DEBUG, "The managed launch outcome is unavailable ({0})", unknown.getClass().getSimpleName());
                result = new LaunchReply(LaunchResult.UNKNOWN, null);
            }
            int status = completeLaunch(server, key, launch.id(), result);
            if (status == HttpURLConnection.HTTP_CONFLICT) {
                return true;
            }
            if (status != HttpURLConnection.HTTP_NO_CONTENT) {
                return false;
            }
            if (result.result() == LaunchResult.CREATED) {
                children.put(result.childId(), opened);
                retained = true;
            }
            return true;
        } finally {
            if (!retained && opened != null) {
                opened.close();
            }
        }
    }

    private static Poll poll(ServerAddress server, String key, Capability capability, ObjectReader launches) throws IOException {
        HttpURLConnection connection = request(server.baseUrl().resolve(NativeWakeProtocol.POLL), key);
        try {
            connection.setRequestProperty(NativeWakeProtocol.CAPABILITIES, capability.name());
            return switch (connection.getResponseCode()) {
                case 200 -> readPoll(connection, launches);
                case 204 -> new Poll(null, null, null);
                case 410 -> null;
                default -> throw new IOException("The native wake poll was refused.");
            };
        } finally {
            connection.disconnect();
        }
    }

    private static Poll readPoll(HttpURLConnection connection, ObjectReader launches) throws IOException {
        MediaType type = MediaType.parseMediaType(connection.getContentType());
        String childId = connection.getHeaderField(NativeWakeProtocol.CHILD_ID);
        if (childId != null && (!NativeWakeProtocol.validConversation(childId) || !MediaType.TEXT_PLAIN.includes(type))) {
            throw new IOException("The native wake poll returned an invalid conversation address.");
        }
        try (InputStream input = connection.getInputStream()) {
            if (MediaType.APPLICATION_JSON.includes(type)) {
                byte[] body = input.readNBytes(NativeWakeProtocol.LAUNCH_LIMIT + 1);
                if (body.length > NativeWakeProtocol.LAUNCH_LIMIT) {
                    throw new IOException("The launch exceeds the worker protocol limit.");
                }
                Launch launch = launches.readValue(body);
                if (launch == null) {
                    throw new IOException("The launch was missing.");
                }
                return new Poll(null, null, launch);
            }
            if (MediaType.TEXT_PLAIN.includes(type)) {
                return new Poll(new String(input.readNBytes(65), StandardCharsets.US_ASCII), childId, null);
            }
            throw new IOException("The native wake poll returned an invalid content type.");
        }
    }

    private static boolean complete(ServerAddress server, String key, String marker, String childId, NativeWakeProtocol.Result outcome) throws IOException {
        URI uri = UriComponentsBuilder.fromUri(server.baseUrl()).path(NativeWakeProtocol.COMPLETE).pathSegment(outcome.name()).build().toUri();
        HttpURLConnection connection = request(uri, key);
        try {
            connection.setRequestProperty(NativeWakeProtocol.MARKER, marker);
            if (childId != null) {
                connection.setRequestProperty(NativeWakeProtocol.CHILD_ID, childId);
            }
            return connection.getResponseCode() == 204;
        } finally {
            connection.disconnect();
        }
    }

    private static int completeLaunch(ServerAddress server, String key, String id, LaunchReply outcome) throws IOException {
        URI uri = UriComponentsBuilder.fromUri(server.baseUrl()).path(NativeWakeProtocol.LAUNCH_COMPLETE).pathSegment(outcome.result().name()).build().toUri();
        HttpURLConnection connection = request(uri, key);
        try {
            connection.setRequestProperty(NativeWakeProtocol.LAUNCH_ID, id);
            if (outcome.childId() != null) {
                connection.setRequestProperty(NativeWakeProtocol.CHILD_ID, outcome.childId());
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection request(URI uri, String key) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(12_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(NativeWakeProtocol.KEY, key);
        return connection;
    }
}

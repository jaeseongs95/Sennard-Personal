// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHost;

/**
 * The AOT cache of the hook client (JDK 25 "Ahead-of-Time Cache"). A host starts a new JVM for every command hook, so the cache is what keeps that start short. The cache
 * belongs to the application as the class path names it, to one JDK release and to one OS and CPU, and a JAR changed after the cache was made makes it unusable, so it is made
 * here, from the installed copy, by the {@code java} the installed definitions name, with the options they run it with ({@link HookClientCommand}; training and creation use
 * {@link JvmOptions#hookClientTraining} and {@link JvmOptions#hookClientCacheCreation}, which differ from the final options in the AOT step only). The client program is the
 * {@code java} and the client's jar: there is no other program in the way.
 *
 * <p>The training is one hook run with a Gemini BeforeModel input against a server of its own: a loopback HTTP server that this installer starts for the run, which answers every request
 * with an empty JSON object and is announced in an address file as the Sennard server is ({@link ServerAddress}, naming this process, which lives). The run therefore takes the path that nearly
 * every hook takes (read the address file, check that its process lives, connect, post, read the answer), so the cache holds the classes of that path, not only those of a hook that finds
 * no server. It is not the Sennard server: the server's session store would otherwise receive an invented call. A missing cache costs start-up time only, so a failed
 * training is reported and does not stop the install. The MCP relay has its own main class but shares the client's jar, and the cache is made for the class path.
 *
 * <p>A JVM records one run, and Antigravity, the other host that can only run a command, has its own fields of the input (another file of the client's {@code sennard-hook-input}
 * and another shape of input), so the same run also reads an Antigravity PreToolUse input first ({@link HookClientCall#WARM}) and drops the result.
 */
final class CommandAotCache {
    /** The input of the training run, a Gemini BeforeModel call, in the jar so that the build's and the installer's training read the same file. */
    static final String TRAINING_INPUT = "aot/gemini-before-model.json";
    /** The Antigravity input the training run reads besides the Gemini one, in the jar for the same reason. */
    static final String WARM_INPUT = "aot/antigravity-pre-tool-use.json";
    /** Where the installer puts {@link #WARM_INPUT} for the run, in the version's folder beside the recording; removed once the cache exists. */
    private static final String WARM_FILE = "sennard-aot-antigravity-input.json";
    /** The address file the training run is given: it names the training's own server ({@link #answeringServer}) and is removed when the training ends. */
    static final String TRAINING_ADDRESS_FILE = "sennard-aot-training-server.properties";
    /** What the training's server answers with: an empty JSON object, which is a 200 answer that no host reads. */
    private static final String TRAINING_ANSWER = "{}";
    /** What the client exits with when the server does not answer ({@link FailureAnswer#FAIL}): the run is still a recorded run, so it is accepted, and the cache holds what that run loaded. */
    private static final int NO_SERVER = 1;
    private static final Duration STEP = Duration.ofMinutes(3);

    private final Commands commands;
    private final PrintStream out;

    CommandAotCache(Commands commands, PrintStream out) {
        this.commands = commands;
        this.out = out;
    }

    /** The arguments of the training run: a hook of a host with a call that the training's server answers. */
    static List<String> hookArguments(Path addressFile) {
        List<String> arguments = new ArrayList<>(HookClientCall.of(GeminiHost.ID.label(), "BeforeModel", FailureAnswer.FAIL).arguments());
        arguments.add(HookClientCall.SERVER_FILE + "=" + addressFile);
        return arguments;
    }

    /** {@link #hookArguments} and the Antigravity input to read first: what the training runs. */
    static List<String> trainingArguments(Path addressFile, Path warmFile) {
        List<String> arguments = hookArguments(addressFile);
        arguments.add(HookClientCall.WARM + "=" + AntigravityHost.ID.label() + "=" + warmFile);
        return arguments;
    }

    /** Records the training run and creates the cache from it. */
    void make(InstalledVersion version, Path java) {
        Path configuration = version.folder().resolve(JvmOptions.HOOK_AOT_CONFIGURATION_FILE);
        Path cache = version.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE);
        Path warm = version.folder().resolve(WARM_FILE);
        Path address = version.folder().resolve(TRAINING_ADDRESS_FILE);
        String step = "record";
        HttpServer server = null;
        try {
            byte[] input = resource(TRAINING_INPUT);
            writeWarmInput(warm);
            server = answeringServer(address);
            Commands.Result result = run(java, version, JvmOptions.hookClientTraining(version), input, warm, address);
            boolean recorded = (result.exitCode() == 0 || result.exitCode() == NO_SERVER) && Files.exists(configuration);
            if (recorded) {
                step = "create";
                result = run(java, version, JvmOptions.hookClientCacheCreation(version), input, warm, address);
            }
            if (!recorded || result.exitCode() != 0 || !Files.exists(cache)) {
                out.println("warning: AOT training of the hook client failed in " + step + " (exit " + result.exitCode() + "); hooks will start without the cache: " + result.output().strip());
            }
        } finally {
            if (server != null) {
                server.stop(0);
            }
            for (Path file : List.of(configuration, warm, address)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cause) {
                    out.println("warning: could not remove " + file + ": " + cause);
                }
            }
        }
    }

    private Commands.Result run(Path java, InstalledVersion version, List<String> jvmOptions, byte[] input, Path warm, Path address) {
        List<String> argv = new ArrayList<>(HookClientCommand.withOptions(java, version, jvmOptions));
        argv.addAll(trainingArguments(address, warm));
        return commands.run(argv, Map.of(), input, STEP);
    }

    /**
     * The training's server: it listens on the loopback address at a port the operating system gives (as the Sennard server does), answers every request with {@link #TRAINING_ANSWER}, and is
     * announced in {@code addressFile} as the process of this installer, which lives while the training runs.
     */
    private static HttpServer answeringServer(Path addressFile) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "trainingServerUnavailable", "file", addressFile.toString()), cause);
        }
        byte[] answer = TRAINING_ANSWER.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(answer);
                }
            }
        });
        server.start();
        try {
            ServerAddress.ofThisProcess(server.getAddress().getPort()).writeAtomically(addressFile);
        } catch (IOException cause) {
            server.stop(0);
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unwritable", "file", addressFile.toString()), cause);
        }
        return server;
    }

    private static void writeWarmInput(Path file) {
        try {
            Files.write(file, resource(WARM_INPUT));
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unwritable", "file", file.toString()), cause);
        }
    }

    private static byte[] resource(String name) {
        try {
            return new ClassPathResource(name).getContentAsByteArray();
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "trainingInputUnreadable", "file", name), cause);
        }
    }
}

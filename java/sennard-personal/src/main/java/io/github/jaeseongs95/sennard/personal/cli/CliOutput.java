// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.io.InputStream;
import java.io.PrintStream;

import io.github.jaeseongs95.sennard.attachmentcli.UploadClient;
import io.github.jaeseongs95.sennard.domain.error.SennardException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Where a command's result, its error lines and its usage go: the one output object of the CLI. {@code main} makes it once from the
 * standard streams; the CLI has no Spring context of its own to inject it from, so the one JSON mapper of its results is made here too.
 */
final class CliOutput {
    private final PrintStream out;
    private final PrintStream err;
    private final JsonMapper json = JsonMapper.builder().build();
    private final InputStream in;
    private final CliSentences sentences = new CliSentences(json);

    CliOutput(PrintStream out, PrintStream err) {
        this(out, err, InputStream.nullInputStream());
    }

    /** @param in what the {@code hook} command reads: the host's JSON, which the host writes to the command's standard input */
    CliOutput(PrintStream out, PrintStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
    }

    InputStream input() {
        return in;
    }

    /** The CLI's one JSON mapper, for the {@code hook} command, which binds a host's input to a record with it. */
    JsonMapper mapper() {
        return json;
    }

    /** The error stream, for the {@code hook} command, which gives the host raw bytes. */
    PrintStream errorStream() {
        return err;
    }

    /** The result stream, for the installer, which writes its progress lines itself. */
    PrintStream stream() {
        return out;
    }

    /** A result line on stdout. */
    void result(String line) {
        out.println(line);
    }

    /** A result on stdout as one line of JSON. */
    void json(Object value) {
        out.println(json.writeValueAsString(value));
    }

    /** A result on stdout as indented JSON. */
    void prettyJson(Object value) {
        out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /**
     * The line a failed command ends with: {@code CODE: what to do}. The sentence is the catalog's ({@code locales/<language>/cli.json}), made here and nowhere else in this
     * process from the failure's key and arguments; the code is the constant's name. It is English: a person or an agent reads it on a terminal and no language is asked for.
     */
    void failure(SennardException failure) {
        error(failure.code().name() + ": " + sentences.of(failure) + violations(failure));
    }

    /** What a refused input says is wrong with it ({@code details.violations}, as {@code ARTIFACT_*} and the commands' own input errors write it), after the sentence; nothing when there is none. */
    private static String violations(SennardException failure) {
        return failure.details().get("violations") instanceof java.util.List<?> found && !found.isEmpty()
                ? " (" + String.join("; ", found.stream().map(String::valueOf).toList()) + ")" : "";
    }

    /** The line a tool's refusal ends a command with: the tool's own code and sentence, as {@code CODE: sentence} (the upload client's refusals). */
    void refusal(UploadClient.Refused refused) {
        error(refused.code() + ": " + refused.getMessage());
    }

    /** A catalog sentence of a command that succeeded (English, as the failure lines are). */
    String sentence(String key) {
        return sentences.of(key);
    }

    /** What a failure tells, for a caller that puts it in a line of its own (the hook client's answer to its host). */
    CliSentences sentences() {
        return sentences;
    }

    /** The courtesy line of a failed command could not be made either: what stopped it is said after the failure, not instead of it. */
    void hintUnavailable(RuntimeException cause) {
        error("The installed command is not known either: " + cause.getMessage());
    }

    /** An error or usage line on stderr. */
    void error(String line) {
        err.println(line);
    }

    void flush() {
        out.flush();
        err.flush();
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Edits only the Claude Code user setting that would otherwise end repeated Stop continuations. */
final class ClaudeStopSettings {
    static final String KEY = "CLAUDE_CODE_STOP_HOOK_BLOCK_CAP";
    private static final String DISABLED = "\"0\"";
    private static final String ENV = "env";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private ClaudeStopSettings() {
    }

    static Optional<Change> prepare(InstallEnvironment environment, ClaudeStopConfirmation confirmation) {
        try {
            boolean registered = !new ClaudePackageLocation(JSON).scan(environment).packages().isEmpty();
            return registered ? prepare(ClaudePackageLocation.userSettings(environment), confirmation) : Optional.empty();
        } catch (IOException | JacksonException failure) {
            throw hostFilesFailed(failure.getMessage(), failure);
        }
    }

    /** Reads and confirms before the installer writes any version, hook or settings files. */
    static Optional<Change> prepare(Path settings, ClaudeStopConfirmation confirmation) throws IOException {
        Path file = settings.toAbsolutePath().normalize();
        if (Files.exists(file) || Files.isSymbolicLink(file)) {
            file = file.toRealPath();
        }
        String before = read(file);
        Layout root = layout(before);
        Optional<Field> env = root.field(ENV);
        Optional<Field> cap = env.flatMap(field -> field.object().field(KEY));
        Optional<String> previous = cap.map(field -> field.value(before));
        if (previous.map(ClaudeStopSettings::disabled).orElse(false)) {
            return Optional.empty();
        }
        if (previous.isPresent()) {
            String conflict = KEY + " in " + file + " = " + previous.get();
            ClaudeStopConfirmation.Answer answer = confirmation.confirm(conflict
                    + "\nChange this value to \"0\" and continue Sennard setup?"
                    + "\n이 값을 \"0\"으로 바꾸고 Sennard 설정을 계속하시겠습니까?");
            if (answer != ClaudeStopConfirmation.Answer.YES) {
                String reason = answer == ClaudeStopConfirmation.Answer.NO
                        ? "Change declined. Sennard setup stopped. / 변경을 거절해 Sennard 설정을 중단했습니다."
                        : "Confirmation unavailable. Sennard setup stopped. / 확인 응답을 받을 수 없어 Sennard 설정을 중단했습니다.";
                throw hostFilesFailed(reason + " " + conflict, null);
            }
        }

        String after;
        if (env.isEmpty()) {
            after = root.insert(before, "\"env\": {" + capMember() + "}");
        } else if (cap.isEmpty()) {
            after = env.get().object().insert(before, capMember());
        } else {
            Field existing = cap.get();
            after = replace(before, existing.valueStart(), existing.end(), DISABLED);
        }
        return Optional.of(new Change(file, before, after));
    }

    private static String capMember() {
        return "\"" + KEY + "\": " + DISABLED;
    }

    record Change(Path file, String before, String after) {
        void apply() {
            try {
                if (!read(file).equals(before)) {
                    throw new IOException("Claude Code settings changed during Sennard setup. Run setup again. / 설정이 바뀌었습니다. 다시 실행해 주세요. " + file);
                }
                write(file, after);
            } catch (IOException failure) {
                throw hostFilesFailed(failure.getMessage(), failure);
            }
        }
    }

    private static boolean disabled(String value) {
        JsonNode node = JSON.readTree(value);
        return node.isString() && node.asString().equals("0");
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "{}";
    }

    private static UnavailableException hostFilesFailed(String reason, Exception cause) {
        return new UnavailableException(UnavailableCode.HOST_FILES_FAILED, SennardException.args("cause", reason), cause);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".sennard-", ".tmp");
        try {
            if (Files.exists(file)) {
                Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.writeString(temporary, content);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Jackson supplies the character boundaries. Only the selected member and its separator are edited. */
    private static Layout layout(String text) throws IOException {
        int bom = text.startsWith("\uFEFF") ? 1 : 0;
        try (JsonParser parser = JSON.createParser(text.substring(bom))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Claude Code settings must be a JSON object.");
            }
            Layout root = readObject(parser, bom, true);
            if (parser.nextToken() != null) {
                throw new IOException("Claude Code settings must contain one JSON object.");
            }
            return root;
        }
    }

    private static Layout readObject(JsonParser parser, int bom, boolean root) throws IOException {
        int start = (int) parser.currentTokenLocation().getCharOffset() + bom;
        List<Field> fields = new ArrayList<>();
        for (JsonToken token = parser.nextToken(); token == JsonToken.PROPERTY_NAME; token = parser.nextToken()) {
            String name = parser.currentName();
            int nameStart = (int) parser.currentTokenLocation().getCharOffset() + bom;
            JsonToken type = parser.nextToken();
            int valueStart = (int) parser.currentTokenLocation().getCharOffset() + bom;
            Layout object = null;
            if (root && name.equals(ENV)) {
                if (type != JsonToken.START_OBJECT) {
                    throw new IOException("Claude Code settings env must be a JSON object.");
                }
                object = readObject(parser, bom, false);
            } else {
                parser.skipChildren();
                parser.finishToken();
            }
            int end = (int) parser.currentLocation().getCharOffset() + bom;
            fields.add(new Field(name, nameStart, valueStart, end, object));
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            throw new IOException("Claude Code settings JSON object is incomplete.");
        }
        int close = (int) parser.currentTokenLocation().getCharOffset() + bom;
        return new Layout(start, close, fields);
    }

    private record Field(String name, int nameStart, int valueStart, int end, Layout object) {
        String value(String text) {
            return text.substring(valueStart, end);
        }
    }

    private record Layout(int start, int close, List<Field> fields) {
        Optional<Field> field(String name) {
            return fields.stream().filter(field -> field.name().equals(name)).findFirst();
        }

        String insert(String text, String member) {
            if (fields.isEmpty()) {
                return replace(text, close, close, member);
            }
            int first = fields.getFirst().nameStart();
            return replace(text, first, first, member + "," + text.substring(start + 1, first));
        }
    }

    private static String replace(String text, int start, int end, String value) {
        return text.substring(0, start) + value + text.substring(end);
    }
}

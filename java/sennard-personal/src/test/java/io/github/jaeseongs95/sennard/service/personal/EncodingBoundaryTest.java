// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * "UTF-8 everywhere", read from the compiled classes of every module so a later change that goes back to the platform's charset fails
 * here and not on a Korean PC: no API that picks a charset for the caller, nobody reads a tool's pipe by hand, and every {@code main}
 * fixes the standard streams first.
 */
class EncodingBoundaryTest {
    private static final String BOUNDARY = "io/github/jaeseongs95/sennard/protocol/Utf8Boundary";
    private static final String PIPE_READER = "io/github/jaeseongs95/sennard/personal/ProcessOutput";
    private static final String HOST_STDIO = "io/github/jaeseongs95/sennard/protocol/ToolProcess$Stdio";
    private static final Reference PROCESS_STDOUT = new Reference("java/lang/Process", "getInputStream", "()Ljava/io/InputStream;");
    private static final Set<String> PROCESS_READERS = Set.of("getInputStream", "getErrorStream", "inputReader", "errorReader", "outputWriter");
    private static final String CHARSET = "Ljava/nio/charset/Charset;";

    private record Reference(String owner, String name, String type) {
        @Override
        public String toString() {
            return owner + "." + name + type;
        }

        boolean constructorWithoutCharset(String of) {
            return owner.equals(of) && name.equals("<init>") && !type.contains(CHARSET);
        }

        /** The method or constructor leaves the choice of charset to the JVM. */
        boolean picksACharsetForTheCaller() {
            return owner.equals("java/lang/String") && ((name.equals("getBytes") && type.equals("()[B"))
                    || (name.equals("<init>") && (type.equals("([B)V") || type.equals("([BII)V"))))
                    || constructorWithoutCharset("java/io/FileReader")
                    || constructorWithoutCharset("java/io/FileWriter")
                    || owner.equals("java/io/InputStreamReader") && name.equals("<init>") && type.equals("(Ljava/io/InputStream;)V")
                    || owner.equals("java/io/OutputStreamWriter") && name.equals("<init>") && type.equals("(Ljava/io/OutputStream;)V")
                    || constructorWithoutCharset("java/io/PrintStream")
                    || constructorWithoutCharset("java/io/PrintWriter") && !type.startsWith("(Ljava/io/Writer;")
                    || constructorWithoutCharset("java/util/Scanner") && !type.startsWith("(Ljava/lang/String;)") && !type.startsWith("(Ljava/lang/Readable;)")
                    || owner.equals("java/nio/charset/Charset") && name.equals("defaultCharset")
                    || owner.equals("java/io/ByteArrayOutputStream") && name.equals("toString") && type.equals("()Ljava/lang/String;")
                    || (owner.equals("java/net/URLDecoder") || owner.equals("java/net/URLEncoder")) && type.equals("(Ljava/lang/String;)Ljava/lang/String;")
                    || owner.equals("org/apache/commons/compress/archivers/tar/TarArchiveInputStream") && name.equals("<init>")
                            && !type.contains("Ljava/lang/String;");
        }
    }

    private record Scanned(String className, ClassModel model, List<Reference> references) {
    }

    private static List<Scanned> scan() throws IOException {
        String property = System.getProperty("sennard.encoding.classes");
        assertNotNull(property, "the build passes the compiled classes of every module (sennard-personal/build.gradle.kts)");
        List<Scanned> scanned = new ArrayList<>();
        for (String entry : property.split(File.pathSeparator)) {
            Path directory = Path.of(entry);
            assertTrue(Files.isDirectory(directory), "compiled classes exist: " + directory);
            try (Stream<Path> walk = Files.walk(directory)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                    List<Reference> references = new ArrayList<>();
                    for (PoolEntry poolEntry : model.constantPool()) {
                        if (poolEntry instanceof MethodRefEntry reference) {
                            references.add(new Reference(reference.owner().asInternalName(), reference.name().stringValue(), reference.type().stringValue()));
                        }
                    }
                    scanned.add(new Scanned(model.thisClass().asInternalName(), model, references));
                }
            }
        }
        assertTrue(scanned.size() > 100, "the scan read every module (" + scanned.size() + " classes)");
        return scanned;
    }

    @Test
    void noClassLeavesTheCharsetToTheJvm() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Scanned one : scan()) {
            for (Reference reference : one.references()) {
                if (reference.picksACharsetForTheCaller()) {
                    offenders.add(one.className() + " -> " + reference);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    /**
     * ToolProcess.run and PgServer capture output in a file so a process cannot hold a pipe open past the caller's deadline.
     * {@link #PIPE_READER} streams output from a user's test command, reading only available bytes and closing its pipes when the run ends.
     * {@link #HOST_STDIO} exposes stdout for live host protocols, which own their deadlines and close the process.
     * Both exceptions must still be in use. Host stdio may acquire only the raw stdout stream from Process.
     */
    @Test
    void noClassReadsAToolsPipeByItself() throws IOException {
        List<String> offenders = new ArrayList<>();
        boolean commandReaderUsed = false;
        boolean hostStdoutUsed = false;
        for (Scanned one : scan()) {
            for (Reference reference : one.references()) {
                if (reference.owner().equals("java/lang/Process") && PROCESS_READERS.contains(reference.name())) {
                    if (one.className().equals(PIPE_READER)) {
                        commandReaderUsed = true;
                    } else if (one.className().equals(HOST_STDIO) && reference.equals(PROCESS_STDOUT)) {
                        hostStdoutUsed = true;
                    } else {
                        offenders.add(one.className() + " -> " + reference);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
        assertTrue(commandReaderUsed, "remove the pipe exception when " + PIPE_READER + " stops using it");
        assertTrue(hostStdoutUsed, "remove the stdout exception when " + HOST_STDIO + " stops using it");
    }

    @Test
    void everyMainFixesTheStandardStreams() throws IOException {
        int mains = 0;
        List<String> offenders = new ArrayList<>();
        for (Scanned one : scan()) {
            boolean hasMain = false;
            for (MethodModel method : one.model().methods()) {
                hasMain |= method.methodName().equalsString("main") && method.methodType().equalsString("([Ljava/lang/String;)V")
                        && (method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0;
            }
            if (!hasMain) {
                continue;
            }
            mains++;
            if (one.references().stream().noneMatch(r -> r.owner().equals(BOUNDARY) && r.name().equals("fixStandardStreams"))) {
                offenders.add(one.className());
            }
        }
        assertTrue(mains >= 2, "the service and the CLI are entry points (" + mains + ")");
        assertEquals(List.of(), offenders);
    }
}

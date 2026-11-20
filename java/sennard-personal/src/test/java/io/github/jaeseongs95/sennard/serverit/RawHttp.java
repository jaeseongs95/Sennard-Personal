// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP over a plain socket: a test must be able to send the Host, Origin, absent or oversized headers that a real HTTP client refuses to
 * produce, and to read the status line without following the framing's rules.
 */
final class RawHttp {
    record Response(int status, Map<String, String> headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private RawHttp() {
    }

    /** One request on a new connection, closed afterwards. A null body sends none; a body with no Content-Length header goes chunked. */
    static Response exchange(int port, String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Host", "127.0.0.1:" + port);
        all.putAll(headers);
        all.put("Connection", "close");
        all.values().removeIf(java.util.Objects::isNull);
        try (Socket socket = connect(port)) {
            socket.setSoTimeout(60_000);
            StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean chunked = body != null && !all.containsKey("Content-Length");
            if (chunked) {
                all.put("Transfer-Encoding", "chunked");
            }
            all.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("\r\n");
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
            try {
                if (chunked) {
                    int offset = 0;
                    while (offset < body.length) {
                        int size = Math.min(1 << 20, body.length - offset);
                        socket.getOutputStream().write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(body, offset, size);
                        socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.UTF_8));
                        offset += size;
                    }
                    socket.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                } else if (body != null) {
                    socket.getOutputStream().write(body);
                }
                socket.getOutputStream().flush();
            } catch (IOException peerStoppedReading) {
                // A server that refuses a body may close before all of it is written; the answer it sent is what is being tested.
            }
            return parse(socket.getInputStream());
        }
    }

    /** A loopback connect to a port nobody listens on can land on itself when the OS picks that same port for the client; that is not a server. */
    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        if (socket.getLocalPort() == port) {
            socket.close();
            throw new ConnectException("Connected to itself: nothing listens on " + port);
        }
        return socket;
    }

    private static Response parse(InputStream in) throws IOException {
        String head = readHead(in);
        Map<String, String> headers = headers(head);
        byte[] body = in.readAllBytes();
        return new Response(status(head), headers, "chunked".equalsIgnoreCase(headers.get("transfer-encoding")) ? dechunked(body) : body);
    }

    private static byte[] dechunked(byte[] framed) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int at = 0;
        while (at < framed.length) {
            int lineEnd = at;
            while (lineEnd + 1 < framed.length && !(framed[lineEnd] == '\r' && framed[lineEnd + 1] == '\n')) {
                lineEnd++;
            }
            int size = Integer.parseInt(new String(framed, at, lineEnd - at, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
            if (size == 0) {
                break;
            }
            body.write(framed, lineEnd + 2, size);
            at = lineEnd + 2 + size + 2;
        }
        return body.toByteArray();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = 0;
        int blank = 0;
        for (;;) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("The connection closed before the response head ended: " + bytes.toString(StandardCharsets.UTF_8));
            }
            bytes.write(b);
            if (b == '\n') {
                blank = previous == '\r' ? blank + 1 : 0;
                if (blank == 2) {
                    return bytes.toString(StandardCharsets.UTF_8);
                }
            } else if (b != '\r') {
                blank = 0;
            }
            previous = b;
        }
    }

    private static int status(String head) {
        return Integer.parseInt(head.split(" ", 3)[1]);
    }

    private static Map<String, String> headers(String head) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return headers;
    }
}

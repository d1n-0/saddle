package me.d1n0.saddle.dap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * DAP wire format: "Content-Length: N\r\n\r\n" headers followed by a JSON body.
 */
public final class DapProtocol {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final int MAX_HEADER_LENGTH = 8 * 1024;
    private static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;

    private DapProtocol() {}

    /** Reads one message; returns null on a clean end of stream. */
    public static Map<String, Object> readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(64);
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (headerBuf.size() > MAX_HEADER_LENGTH) throw new IOException("DAP header too large");
            state = switch (state) {
                case 0, 2 -> (b == '\r') ? state + 1 : 0;
                case 1, 3 -> (b == '\n') ? state + 1 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
            if (state == 4) break;
        }
        if (b == -1) {
            if (headerBuf.size() == 0) return null;
            throw new EOFException("Truncated DAP header");
        }

        int contentLength = parseContentLength(headerBuf.toString(StandardCharsets.US_ASCII));
        if (contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
            throw new IOException("Invalid Content-Length: " + contentLength);
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) throw new EOFException("Truncated DAP body");
        return GSON.fromJson(new String(body, StandardCharsets.UTF_8), MAP_TYPE);
    }

    private static int parseContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    public static void writeMessage(OutputStream out, Object message) throws IOException {
        byte[] body = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}

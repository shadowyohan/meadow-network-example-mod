package im.shadowyohan.meadow.best.client.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// собирает multipart/form-data тело в память - для /upload/image (java.net.http не умеет это из коробки)
public final class MultipartBodyPublisher {

    private MultipartBodyPublisher() {
    }

    public static byte[] single(String boundary, String fieldName, String filename,
                                 String contentType, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 256);
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n");
        writeAscii(out, "Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(data);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}

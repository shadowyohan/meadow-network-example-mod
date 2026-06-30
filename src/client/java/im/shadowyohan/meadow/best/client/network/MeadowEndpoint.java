package im.shadowyohan.meadow.best.client.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * конфиг подключения к meadow!network + подпись логина.
 * client_id/client_secret выдаю я, подставь свои. секрет по сети не уходит,
 * им только подписывается логин (hmac-sha256), сервер сверяет сам.
 */

public final class MeadowEndpoint {

    private MeadowEndpoint() {
    }

    public static final int CLIENT_ID = 0; // вставьте свой
    // секрет визуала, в реальном клиенте прячется за протой
    public static final String CLIENT_SECRET = "your-secret";
    public static final String BASE_URL = "http://31.76.28.181:1488";

    // hmac_sha256(secret, "client_id:uid:username:timestamp") в hex - то что проверяет сервер
    public static String sign(String uid, String username, long timestamp) {
        String message = CLIENT_ID + ":" + uid + ":" + username + ":" + timestamp;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    // http(s) -> ws(s) для вебсокетов
    public static String wsBase() {
        if (BASE_URL.startsWith("https://")) {
            return "wss://" + BASE_URL.substring("https://".length());
        }
        if (BASE_URL.startsWith("http://")) {
            return "ws://" + BASE_URL.substring("http://".length());
        }
        return BASE_URL;
    }
}

package im.shadowyohan.meadow.best.client.network;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * картинки из сообщений чата: в отличие от AvatarRenderer, тут нужен постоянный дисковый кэш
 * (задание - "кэш пускай хранится всегда") и сохранение пропорций (не квадратная обрезка).
 */
public final class ImageCache {

    private ImageCache() {
    }

    static {
        ImageIO.setUseCache(false);
    }

    // больше этой стороны не рендерим - экономим VRAM, превью в баблах всё равно маленькое
    private static final int MAX_SIDE = 480;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "meadow-imgcache");
        t.setDaemon(true);
        return t;
    });

    public enum State {LOADING, READY, FAILED}

    public static final class Handle {
        public final Identifier texture;
        public final int width;
        public final int height;

        Handle(Identifier texture, int width, int height) {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile BufferedImage decoded;
        volatile Handle handle;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** готовая текстура (с исходными пропорциями) или null пока грузится/декодится/не начато. */
    public static Handle resolve(String rawUrl) {
        String url = normalize(rawUrl);
        if (url == null) return null;
        Entry e = CACHE.get(url);
        if (e == null) {
            e = new Entry();
            CACHE.put(url, e);
            loadAsync(url, e);
            return null;
        }
        return resolveEntry(url, e);
    }

    /** превью локального файла (пока картинка аплоадится на сервер, ещё нет image_url). */
    public static Handle resolveLocal(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return null;
        String key = "local:" + absolutePath;
        Entry e = CACHE.get(key);
        if (e == null) {
            e = new Entry();
            CACHE.put(key, e);
            Entry finalE = e;
            IO.execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(Path.of(absolutePath));
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
                    if (decoded == null) throw new IllegalStateException("unsupported image format");
                    if (decoded.getWidth() > MAX_SIDE || decoded.getHeight() > MAX_SIDE) {
                        decoded = downscale(decoded, MAX_SIDE);
                    }
                    finalE.decoded = decoded;
                    finalE.state = State.READY;
                } catch (Throwable t) {
                    finalE.state = State.FAILED;
                }
            });
            return null;
        }
        return resolveEntry(key, e);
    }

    private static Handle resolveEntry(String key, Entry e) {
        if (e.state != State.READY) return null;
        if (e.handle != null) return e.handle;
        if (e.decoded == null) return null;
        try {
            NativeImage img = toNative(e.decoded);
            Identifier id = Identifier.of("meadow-network", "chatimg/" + Integer.toHexString(key.hashCode()));
            var tex = new NativeImageBackedTexture(img);
            tex.setFilter(true, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            Handle h = new Handle(id, e.decoded.getWidth(), e.decoded.getHeight());
            e.handle = h;
            e.decoded = null;
            return h;
        } catch (Throwable t) {
            e.state = State.FAILED;
            return null;
        }
    }

    // спиннер загрузки без ассетов - восемь точек по кругу, "бегущая" яркая
    public static void drawSpinner(DrawContext ctx, int x, int y, int size) {
        int dots = 8;
        int cx = x + size / 2;
        int cy = y + size / 2;
        double r = size / 2.0 - 2;
        int active = (int) ((System.currentTimeMillis() / 90) % dots);
        for (int i = 0; i < dots; i++) {
            double angle = (Math.PI * 2 * i) / dots;
            int dx = cx + (int) Math.round(Math.cos(angle) * r);
            int dy = cy + (int) Math.round(Math.sin(angle) * r);
            int alpha = i == active ? 0xFF : 0x40;
            int color = (alpha << 24) | 0xFFFFFF;
            ctx.fill(dx - 1, dy - 1, dx + 1, dy + 1, color);
        }
    }

    private static void loadAsync(String url, Entry e) {
        IO.execute(() -> {
            try {
                Path cacheFile = cacheFileFor(url);
                byte[] data;
                if (Files.isRegularFile(cacheFile)) {
                    data = Files.readAllBytes(cacheFile);
                } else {
                    data = download(url);
                    if (data == null) {
                        e.state = State.FAILED;
                        return;
                    }
                    try {
                        Files.createDirectories(cacheFile.getParent());
                        Files.write(cacheFile, data);
                    } catch (Throwable ignored) {
                        // не смогли закэшировать на диск - не страшно, просто перекачаем в след раз
                    }
                }

                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
                if (decoded == null) throw new IllegalStateException("unsupported image format");
                if (decoded.getWidth() > MAX_SIDE || decoded.getHeight() > MAX_SIDE) {
                    decoded = downscale(decoded, MAX_SIDE);
                }
                e.decoded = decoded;
                e.state = State.READY;
            } catch (Throwable t) {
                System.out.println("[meadow-net] chat image load failed for " + url + ": " + t);
                e.state = State.FAILED;
            }
        });
    }

    private static byte[] download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) return null;
            return resp.body();
        } catch (Throwable t) {
            return null;
        }
    }

    private static BufferedImage downscale(BufferedImage src, int maxSide) {
        float scale = (float) maxSide / Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static NativeImage toNative(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage native_ = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                native_.setColorArgb(x, y, src.getRGB(x, y));
            }
        }
        return native_;
    }

    private static Path cacheFileFor(String url) {
        return cacheDir().resolve(sha256(url) + ".img");
    }

    private static Path cacheDir() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("meadow-network").resolve("imagecache");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // относительный путь с бэка -> абсолютный
    private static String normalize(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return MeadowEndpoint.BASE_URL + (url.startsWith("/") ? url : "/" + url);
    }
}

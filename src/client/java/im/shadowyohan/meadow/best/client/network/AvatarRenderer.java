package im.shadowyohan.meadow.best.client.network;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * грузит аватарки/лого по url и кэширует как ванильные текстуры.
 * нет url или не прогрузилось -> рисуем букву-фолбэк.
 * тут example-mod, поэтому просто квадрат без круглой маски (в основном клиенте
 * это в кастомном рендере). растеризуем в RASTER и рисуем с билинейкой,
 * чтобы мелкие аватарки не были пиксельными.
 */
public final class AvatarRenderer {

    private AvatarRenderer() {
    }

    static {
        // дисковый кэш ImageIO иногда мешает - читаем из памяти
        ImageIO.setUseCache(false);
    }

    // в этом разрешении держим текстуру, downscale до нужного размера даёт билинейка
    private static final int RASTER = 128;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private enum State {LOADING, READY, FAILED}

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile byte[] data;
        volatile BufferedImage decoded;
        volatile Identifier texture;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private static final int[] PALETTE = {
            0xFF9D6CFF, 0xFF6CAAFF, 0xFFFF8A6C,
            0xFF6CFFAA, 0xFFFFC46C, 0xFFE46CFF,
    };

    // сбрасывает кэш аватарок - дёргается при (пере)подключении к сети, чтобы не тащить
    // чужие/устаревшие аватарки между аккаунтами. кэш картинок сообщений (ImageCache) сюда не входит
    public static void clearCache() {
        CACHE.clear();
    }

    // квадратный аватар по url, иначе буква fallbackName (пустое имя - не рисуем вообще)
    public static void draw(DrawContext ctx, String url, String fallbackName, int x, int y, int size) {
        Identifier tex = resolve(url);
        if (tex != null) {
            blit(ctx, tex, x, y, size);
            return;
        }
        drawFallback(ctx, fallbackName, x, y, size);
    }

    // лого визуала (client_logo), нет/не прогрузилось - просто ничего
    public static void drawSquare(DrawContext ctx, String url, int x, int y, int size) {
        Identifier tex = resolve(url);
        if (tex != null) {
            blit(ctx, tex, x, y, size);
        }
    }

    // рисуем всю RASTER-текстуру в квадрат size (12-арг overload = downscale)
    private static void blit(DrawContext ctx, Identifier tex, int x, int y, int size) {
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 0f, 0f, size, size, RASTER, RASTER, RASTER, RASTER);
    }

    private static void drawFallback(DrawContext ctx, String name, int x, int y, int size) {
        if (name == null || name.isBlank()) return;
        int bg = colorFor(name);
        ctx.fill(x, y, x + size, y + size, bg);
        String letter = name.substring(0, 1).toUpperCase();
        var tr = MinecraftClient.getInstance().textRenderer;
        int tw = tr.getWidth(letter);
        ctx.drawText(tr, letter, x + (size - tw) / 2, y + (size - tr.fontHeight) / 2 + 1, 0xFFFFFFFF, false);
    }

    private static int colorFor(String name) {
        if (name == null || name.isEmpty()) return PALETTE[0];
        int h = Math.abs(name.hashCode());
        return PALETTE[h % PALETTE.length];
    }

    // относительный путь с бэка -> абсолютный (client_logo часто отдаётся как /static/...)
    private static String normalize(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return MeadowEndpoint.BASE_URL + (url.startsWith("/") ? url : "/" + url);
    }

    // готовая текстура или null пока грузится/декодится
    private static Identifier resolve(String rawUrl) {
        String url = normalize(rawUrl);
        if (url == null) return null;
        Entry e = CACHE.get(url);
        if (e == null) {
            e = new Entry();
            CACHE.put(url, e);
            download(url, e);
            return null;
        }
        if (e.state != State.READY) return null;
        if (e.texture != null) return e.texture;

        if (e.decoded == null) {
            byte[] data = e.data;
            if (data == null) return null;
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
                if (decoded == null) throw new IllegalStateException("unsupported image format");
                e.decoded = decoded;
                e.data = null;
            } catch (Throwable t) {
                System.out.println("[meadow-net] avatar decode failed for " + url + ": " + t);
                e.state = State.FAILED;
                return null;
            }
        }

        try {
            NativeImage img = squareNative(e.decoded, RASTER);
            Identifier id = Identifier.of("meadow-network", "avatar/" + Integer.toHexString(url.hashCode()));
            var tex = new NativeImageBackedTexture(img);
            tex.setFilter(true, false); // билинейка, без мипмапов
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            e.texture = id;
            e.decoded = null;
            return id;
        } catch (Throwable t) {
            System.out.println("[meadow-net] avatar rasterize failed for " + url + ": " + t);
            e.state = State.FAILED;
            return null;
        }
    }

    // cover-fit обрезка в квадрат RASTER (без круглой маски)
    private static NativeImage squareNative(BufferedImage src, int raster) {
        BufferedImage out = new BufferedImage(raster, raster, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        float scale = (float) raster / Math.min(src.getWidth(), src.getHeight());
        int w = Math.round(src.getWidth() * scale);
        int h = Math.round(src.getHeight() * scale);
        g.drawImage(src, (raster - w) / 2, (raster - h) / 2, w, h, null);
        g.dispose();

        NativeImage native_ = new NativeImage(raster, raster, true);
        for (int y = 0; y < raster; y++) {
            for (int x = 0; x < raster; x++) {
                // getRGB и setColorArgb оба в argb, свопать не надо
                native_.setColorArgb(x, y, out.getRGB(x, y));
            }
        }
        return native_;
    }

    private static void download(String url, Entry e) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((resp, err) -> {
                if (err == null && resp.statusCode() / 100 == 2) {
                    e.data = resp.body();
                    e.state = State.READY;
                } else {
                    e.state = State.FAILED;
                }
            });
        } catch (Throwable t) {
            e.state = State.FAILED;
        }
    }
}

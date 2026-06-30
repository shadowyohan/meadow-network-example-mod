package im.shadowyohan.meadow.best.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MeadowSocket {

    public static final int MAX_RETRIES = 3;

    private final String name;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Supplier<String> urlSupplier;
    private final Consumer<JsonObject> handler;
    private final Consumer<Boolean> onConnectedChanged;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile boolean connected;
    private int retries;

    public MeadowSocket(String name, HttpClient http, ScheduledExecutorService scheduler,
                        Supplier<String> urlSupplier, Consumer<JsonObject> handler,
                        Consumer<Boolean> onConnectedChanged) {
        this.name = name;
        this.http = http;
        this.scheduler = scheduler;
        this.urlSupplier = urlSupplier;
        this.handler = handler;
        this.onConnectedChanged = onConnectedChanged;
    }

    public boolean isConnected() {
        return connected;
    }

    public void start() {
        if (active.compareAndSet(false, true)) {
            retries = 0;
            open();
        }
    }

    public void stop() {
        active.set(false);
        WebSocket s = socket;
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Throwable ignored) {
            }
        }
        socket = null;
        setConnected(false);
    }

    public void send(JsonObject payload) {
        WebSocket s = socket;
        if (s != null && connected) {
            s.sendText(payload.toString(), true);
        }
    }

    private void open() {
        if (!active.get()) return;
        String url = urlSupplier.get();
        if (url == null) {
            scheduleReconnect();
            return;
        }
        http.newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        System.out.println("[meadow-net] " + name + " connect failed: " + err.getMessage());
                        scheduleReconnect();
                    } else {
                        this.socket = ws;
                        this.retries = 0;
                        setConnected(true);
                        System.out.println("[meadow-net] " + name + " connected");
                    }
                });
    }

    private void scheduleReconnect() {
        setConnected(false);
        if (!active.get()) return;
        if (retries >= MAX_RETRIES) {
            System.out.println("[meadow-net] " + name + " giving up after " + MAX_RETRIES + " retries");
            active.set(false);
            return;
        }
        retries++;
        long delay = 1500L * retries; // 1.5s, 3s, 4.5s
        System.out.println("[meadow-net] " + name + " reconnect " + retries + "/" + MAX_RETRIES + " in " + delay + "ms");
        scheduler.schedule(this::open, delay, TimeUnit.MILLISECONDS);
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            if (onConnectedChanged != null) {
                onConnectedChanged.accept(value);
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String full = buffer.toString();
                buffer.setLength(0);
                dispatch(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[meadow-net] " + name + " closed: " + statusCode + " " + reason);
            socket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[meadow-net] " + name + " error: " + error.getMessage());
            socket = null;
            scheduleReconnect();
        }
    }

    private void dispatch(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            handler.accept(obj);
        } catch (Throwable t) {
            System.out.println("[meadow-net] " + name + " bad frame: " + raw);
        }
    }
}

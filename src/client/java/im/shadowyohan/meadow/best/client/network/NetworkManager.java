package im.shadowyohan.meadow.best.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import im.shadowyohan.meadow.best.client.network.model.Conversation;
import im.shadowyohan.meadow.best.client.network.model.Friend;
import im.shadowyohan.meadow.best.client.network.model.Message;
import im.shadowyohan.meadow.best.client.network.model.NetUser;
import im.shadowyohan.meadow.best.client.screen.MessengerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * точка входа клиента в meadow!network. Авторизуется по HMAC-подписи, держит два
 * постоянныъх вебсокета (общий чат и ЛС (в будущем еще будет держать clientchat, чат юзеров внутри одного мода)) с автореконнектом, грузит друзей и историю
 * и хранит состояние, которое читает мессенджер-экран. Вся сетевая работа идёт на фоновом пуле.
 */


public final class NetworkManager {

    public enum Status {OFFLINE, CONNECTING, ONLINE, FAILED}

    /** экран и инициализатор клиента берут менеджер отсюда. */
    private static final NetworkManager INSTANCE = new NetworkManager();

    public static NetworkManager get() {
        return INSTANCE;
    }

    private final HttpClient http = HttpClient.newBuilder()
            // meadow-net работает на python fastapi, это uvicorn - работаент на HTTP/1.1 - без этого java.net.http шлёт h2c-upgrade,
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "meadow-net");
        t.setDaemon(true);
        return t;
    });

    private volatile Status status = Status.OFFLINE;
    private volatile String token;
    private volatile NetUser self;

    private final Conversation general = Conversation.channel("general", "Общий");
    private final Map<Integer, Conversation> dms = new ConcurrentHashMap<>();
    private final List<Friend> friends = new ArrayList<>();

    private MeadowSocket chatSocket;
    private MeadowSocket dmSocket;
    private ScheduledFuture<?> pollTask;

    /** период фонового обновления - подхватывает смену аватарок без релогина. */
    private static final long POLL_INTERVAL_SECONDS = 40L;

    /** to_user_id отправленных лсок в порядке отправки - для разбора эхо dm_sent. */
    private final ConcurrentLinkedDeque<Integer> pendingDmTargets = new ConcurrentLinkedDeque<>();

    // жизненный цикл

    /** запускает подключение в фоне сразу при заходе в клиент. */
    public void start() {
        scheduler.execute(this::connect);
    }

    /** кнопка повторить на экране ошибки. */
    public void retry() {
        if (status == Status.CONNECTING) return;
        scheduler.execute(this::connect);
    }

    private void connect() {
        if (status == Status.CONNECTING || status == Status.ONLINE) return;
        status = Status.CONNECTING;
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        try {
            // личность берём из UserInfo
            String uid;
            String username;
            try {
                uid = UserInfo.uid;
                username = UserInfo.username;
            } catch (Throwable t) {
                // профиль ещё не готов - повторим позже
                status = Status.OFFLINE;
                scheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
                return;
            }

            long ts = System.currentTimeMillis() / 1000L;
            JsonObject body = new JsonObject();
            body.addProperty("client_id", MeadowEndpoint.CLIENT_ID);
            body.addProperty("uid", uid);
            body.addProperty("username", username);
            body.addProperty("timestamp", ts);
            body.addProperty("signature", MeadowEndpoint.sign(uid, username, ts));

            JsonObject login = postJson("/auth/login", body, false);
            if (login == null || !login.has("session_token")) {
                fail("login failed");
                return;
            }
            token = login.get("session_token").getAsString();

            loadSelf();
            loadFriends();
            loadDialogs();
            loadGeneralHistory();

            openSockets();
            status = Status.ONLINE;

            // фоновое обновление self + диалогов - подхватывает смену своей/чужой
            // аватарки без необходимости переподключаться
            pollTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    loadSelf();
                    loadDialogs();
                } catch (Throwable t) {
                    System.out.println("[meadow-net] background poll failed: " + t);
                }
            }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            fail(t.getMessage());
        }
    }

    private void fail(String why) {
        System.out.println("[meadow-net] connect failed: " + why);
        status = Status.FAILED;
    }

    private void openSockets() {
        if (chatSocket != null) chatSocket.stop();
        if (dmSocket != null) dmSocket.stop();

        chatSocket = new MeadowSocket("chat", http, scheduler,
                () -> token == null ? null : MeadowEndpoint.wsBase() + "/ws/chat?token=" + token,
                this::onChatFrame, this::onSocketStateChanged);
        dmSocket = new MeadowSocket("dm", http, scheduler,
                () -> token == null ? null : MeadowEndpoint.wsBase() + "/dm/ws/dm?token=" + token,
                this::onDmFrame, this::onSocketStateChanged);
        chatSocket.start();
        dmSocket.start();
    }

    private void onSocketStateChanged(boolean connected) {
        // Если оба сокета умерли после исчерпания реконнектов - показываем ошибку.
        boolean anyAlive = (chatSocket != null && chatSocket.isConnected())
                || (dmSocket != null && dmSocket.isConnected());
        if (!anyAlive && status == Status.ONLINE) {
            status = Status.FAILED;
        }
    }

    // загрузка данных

    private void loadSelf() {
        JsonObject me = getJson("/users/me");
        if (me == null) return;
        NetUser u = new NetUser();
        u.id = optInt(me, "id", 0);
        u.username = optStr(me, "username", "");
        u.avatarUrl = optStr(me, "avatar_url", null);
        u.clientName = optStr(me, "client_name", null);
        u.clientSlug = optStr(me, "client_slug", null);
        u.clientLogo = optStr(me, "client_logo", null);
        self = u;
    }

    private void loadFriends() {
        JsonObject res = getJson("/friends/");
        if (res == null || !res.has("friends")) return;
        synchronized (friends) {
            friends.clear();
            for (JsonElement el : res.getAsJsonArray("friends")) {
                JsonObject f = el.getAsJsonObject();
                Friend friend = new Friend(
                        optInt(f, "id", 0),
                        optStr(f, "username", ""),
                        optStr(f, "avatar_url", null),
                        optStr(f, "client_name", null),
                        optStr(f, "client_logo", null));
                friends.add(friend);
                // под каждого друга — диалог в сайдбаре (даже без сообщений)
                Conversation conv = dmFor(friend.id, friend.username);
                conv.clientLogo = friend.clientLogo;
                conv.avatarUrl = friend.avatarUrl;
            }
        }
    }

    private void loadDialogs() {
        JsonObject res = getJson("/dm/");
        if (res == null || !res.has("dialogs")) return;
        for (JsonElement el : res.getAsJsonArray("dialogs")) {
            JsonObject d = el.getAsJsonObject();
            int otherId = optInt(d, "other_user_id", 0);
            if (otherId == 0) continue;
            Conversation conv = dmFor(otherId, optStr(d, "other_username", "user"));
            conv.clientLogo = optStr(d, "other_client_logo", conv.clientLogo);
            conv.avatarUrl = optStr(d, "other_avatar_url", conv.avatarUrl);
            if (!optBool(d, "is_read", true) && optInt(d, "sender_id", 0) == otherId) {
                conv.unread = Math.max(conv.unread, 1);
            }
        }
    }

    private void loadGeneralHistory() {
        JsonObject res = getJson("/chat/messages?channel=general&limit=50");
        if (res == null || !res.has("messages")) return;
        synchronized (general.messages) {
            general.messages.clear();
        }
        for (JsonElement el : res.getAsJsonArray("messages")) {
            JsonObject m = el.getAsJsonObject();
            boolean mine = self != null && optStr(m, "username", "").equals(self.username)
                    && nullSafeEq(optStr(m, "client_slug", null), self.clientSlug);
            Message msg = new Message(optLong(m, "id", 0), optStr(m, "username", "?"),
                    optStr(m, "client_logo", null), optStr(m, "client_slug", null),
                    optStr(m, "text", ""), mine);
            // если API отдаёт avatar_url в сообщении — берём его; иначе для своих — self
            String frameAvatar = optStr(m, "avatar_url", null);
            msg.avatarUrl = frameAvatar != null ? frameAvatar : (mine ? selfAvatar() : null);
            general.addMessage(msg);
        }
        general.historyLoaded = true;
    }

    /** ленивая подгрузка истории лсок при открытии диалога. */
    public void ensureHistory(Conversation conv) {
        if (conv == null || conv.historyLoaded || conv.type != Conversation.Type.DM) return;
        conv.historyLoaded = true; // не дёргаем повторно даже при пустом ответе
        scheduler.execute(() -> {
            JsonObject res = getJson("/dm/" + conv.userId + "?limit=50");
            if (res == null || !res.has("messages")) return;
            for (JsonElement el : res.getAsJsonArray("messages")) {
                JsonObject m = el.getAsJsonObject();
                boolean mine = self != null && optInt(m, "sender_id", 0) == self.id;
                Message msg = new Message(optLong(m, "id", 0),
                        optStr(m, "sender_username", mine ? safeSelfName() : conv.title),
                        mine ? selfLogo() : conv.clientLogo, null, optStr(m, "text", ""), mine);
                String senderAvatar = optStr(m, "sender_avatar_url", null);
                msg.avatarUrl = senderAvatar != null ? senderAvatar : (mine ? selfAvatar() : conv.avatarUrl);
                conv.addMessage(msg);
            }
        });
    }

    // входящие вебсокет-фреймы

    private void onChatFrame(JsonObject d) {
        String op = optStr(d, "op", "");
        switch (op) {
            case "message" -> {
                boolean mine = self != null && optStr(d, "username", "").equals(self.username)
                        && nullSafeEq(optStr(d, "client_slug", null), self.clientSlug);
                Message msg = new Message(optLong(d, "id", 0), optStr(d, "username", "?"),
                        optStr(d, "client_logo", null), optStr(d, "client_slug", null),
                        optStr(d, "text", ""), mine);
                // своя аватарка — всегда из актуального селфа, не из (потенциально
                // устаревшего, кэшированного сервером на момент открытия WS) фреймва
                msg.avatarUrl = mine ? selfAvatar() : optStr(d, "avatar_url", null);
                general.addMessage(msg);
                if (!mine && !isViewing(general)) {
                    general.unread++;
                }
            }
            // онлайн счётчик канала шлётся только в user_joined/user_left (включая своё подключение)
            case "user_joined", "user_left" -> {
                if (d.has("online")) {
                    general.onlineCount = optInt(d, "online", general.onlineCount);
                }
            }
            default -> {
            }
        }
    }

    private void onDmFrame(JsonObject d) {
        String op = optStr(d, "op", "");
        switch (op) {
            case "dm" -> {
                int fromId = optInt(d, "from_user_id", 0);
                String username = optStr(d, "username", "user");
                Conversation conv = dmFor(fromId, username);
                conv.clientLogo = optStr(d, "client_logo", conv.clientLogo);
                // сервер кэширует профиль отправителя на момент открытия его WS-сессии,
                // поэтому avatar_url в кадре может быть устаревшим. Доверяем кадру только
                // если другого источника пока нет вовсе (первое сообщение от незнакомца).
                // в будущем зафикшу в самом API
                if (conv.avatarUrl == null || conv.avatarUrl.isBlank()) {
                    conv.avatarUrl = optStr(d, "avatar_url", null);
                }
                String text = optStr(d, "text", "");
                Message incoming = new Message(optLong(d, "id", 0), username,
                        conv.clientLogo, null, text, false);
                incoming.avatarUrl = conv.avatarUrl;
                conv.addMessage(incoming);
                if (!isViewing(conv)) {
                    conv.unread++;
                    notify(Text.literal(username), Text.literal("Новое сообщение: " + text));
                }
            }
            case "dm_sent" -> {
                Integer target = pendingDmTargets.pollFirst();
                int toId = target != null ? target : optInt(d, "to_user_id", 0);
                if (toId != 0) {
                    Conversation conv = dms.get(toId);
                    if (conv != null) {
                        Message sent = new Message(optLong(d, "id", 0), safeSelfName(),
                                selfLogo(), self != null ? self.clientSlug : null,
                                optStr(d, "text", ""), true);
                        // своя аватарка — всегда из актуального self (обновляется поллингом),
                        // а не из кадра: сервер шлёт её из кэша, сделанного при открытии WS
                        sent.avatarUrl = selfAvatar();
                        conv.addMessage(sent);
                    }
                }
            }
            case "presence_init" -> {
                if (d.has("online_friends") && d.get("online_friends").isJsonArray()) {
                    JsonArray arr = d.getAsJsonArray("online_friends");
                    for (JsonElement el : arr) {
                        setOnline(el.getAsInt(), true);
                    }
                }
            }
            case "presence" -> setOnline(optInt(d, "user_id", 0), optBool(d, "online", false));
            case "friend_request" -> notify(
                    Text.literal("Новая заявка в друзья"),
                    Text.literal(optStr(d, "username", "Игрок") + " отправил вам заявку в друзья"));
            case "friend_added" -> {
                notify(Text.literal("Заявка в друзья принята"),
                        Text.literal(optStr(d, "username", "Игрок") + " принял вашу заявку в друзья!"));
                scheduler.execute(this::loadFriends);
            }
            default -> {
            }
        }
    }

    private void setOnline(int userId, boolean online) {
        synchronized (friends) {
            for (Friend f : friends) {
                if (f.id == userId) f.online = online;
            }
        }
        Conversation conv = dms.get(userId);
        if (conv != null) conv.online = online;
    }

    // отправка

    /** отправить сообщение в открытый диалог (канал или ЛС). */
    public void sendMessage(Conversation conv, String text) {
        if (conv == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        if (conv.type == Conversation.Type.CHANNEL) {
            JsonObject p = new JsonObject();
            p.addProperty("op", "message");
            p.addProperty("text", text);
            if (chatSocket != null) chatSocket.send(p);
        } else {
            JsonObject p = new JsonObject();
            p.addProperty("op", "message");
            p.addProperty("to_user_id", conv.userId);
            p.addProperty("text", text);
            pendingDmTargets.addLast(conv.userId);
            if (dmSocket != null) dmSocket.send(p);
        }
    }

    /** пометить диалог прочитанным (сбрасывает счётчик и шлёт read на сервер для ЛС). */
    public void markRead(Conversation conv) {
        if (conv == null) return;
        conv.unread = 0;
        if (conv.type == Conversation.Type.DM) {
            int id = conv.userId;
            scheduler.execute(() -> postJson("/dm/read/" + id, new JsonObject(), true));
        }
    }

    // доступ для UI

    public Status getStatus() {
        return status;
    }

    public NetUser getSelf() {
        return self;
    }

    public Conversation getGeneral() {
        return general;
    }

    public List<Friend> getFriends() {
        synchronized (friends) {
            return new ArrayList<>(friends);
        }
    }

    /** диалоги для секции личные (по друзьям/переписке). */
    public List<Conversation> getDirectConversations() {
        return new ArrayList<>(dms.values());
    }

    /** какой диалог сейчас открыт на экране - задаёт мессенджер, чтобы гасить unread. */
    private volatile Conversation viewing;

    public void setViewing(Conversation conv) {
        this.viewing = conv;
        if (conv != null) markRead(conv);
    }

    private boolean isViewing(Conversation conv) {
        Conversation v = viewing;
        return v != null && v.key().equals(conv.key())
                && MinecraftClient.getInstance().currentScreen instanceof MessengerScreen;
    }

    private Conversation dmFor(int userId, String title) {
        return dms.computeIfAbsent(userId, id -> Conversation.dm(id, title));
    }

    private String safeSelfName() {
        return self != null ? self.username : "you";
    }

    private String selfLogo() {
        return self != null ? self.clientLogo : null;
    }

    private String selfAvatar() {
        return self != null ? self.avatarUrl : null;
    }

    /**
     * Уведомление о событии сети. здесь сделал ванильный тост; в реальном
     * клиенте здесь рисуется кастомный HUD-компонент с аватаркой и лого клиента собеседника.
     */
    private void notify(Text title, Text subtitle) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> SystemToast.show(mc.getToastManager(),
                SystemToast.Type.PERIODIC_NOTIFICATION, title, subtitle));
    }

    // HTTP

    private JsonObject getJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(MeadowEndpoint.BASE_URL + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.out.println("[meadow-net] GET " + path + " -> " + resp.statusCode());
                return null;
            }
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Throwable t) {
            System.out.println("[meadow-net] GET " + path + " err: " + t.getMessage());
            return null;
        }
    }

    private JsonObject postJson(String path, JsonObject body, boolean auth) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(MeadowEndpoint.BASE_URL + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (auth && token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.out.println("[meadow-net] POST " + path + " -> " + resp.statusCode() + " " + resp.body());
                return null;
            }
            if (resp.body() == null || resp.body().isBlank()) return new JsonObject();
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Throwable t) {
            System.out.println("[meadow-net] POST " + path + " err: " + t.getMessage());
            return null;
        }
    }


    private static String optStr(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static int optInt(JsonObject o, String k, int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }

    private static long optLong(JsonObject o, String k, long def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : def;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }

    private static boolean nullSafeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}

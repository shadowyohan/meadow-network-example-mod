package im.shadowyohan.meadow.best.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import im.shadowyohan.meadow.best.client.network.model.Conversation;
import im.shadowyohan.meadow.best.client.network.model.Friend;
import im.shadowyohan.meadow.best.client.network.model.Message;
import im.shadowyohan.meadow.best.client.network.model.NetUser;
import im.shadowyohan.meadow.best.client.network.model.PendingRequest;
import im.shadowyohan.meadow.best.client.screen.MessengerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * точка входа клиента в meadow!network. Авторизуется по HMAC-подписи, держит три
 * постоянных вебсокета (общий чат, ЛС и внутримодовый clientchat) с автореконнектом, грузит друзей и историю
 * и хранит состояние, которое читает мессенджер-экран. Вся сетевая работа идёт на фоновом пуле.
 */


public final class NetworkManager {

    public enum Status {OFFLINE, CONNECTING, ONLINE, FAILED, BANNED, DISCONNECTED}

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
    private final Conversation clientChat = Conversation.channel("client_chat", "Внутренний чат");
    private final Map<Integer, Conversation> dms = new ConcurrentHashMap<>();
    private final List<Friend> friends = new ArrayList<>();

    private volatile List<NetUser> searchResults = List.of();
    private volatile List<PendingRequest> pendingRequests = List.of();

    private MeadowSocket chatSocket;
    private MeadowSocket dmSocket;
    private MeadowSocket clientChatSocket;
    private ScheduledFuture<?> pollTask;

    /** период фонового обновления - подхватывает смену аватарок без релогина. */
    private static final long POLL_INTERVAL_SECONDS = 40L;

    /**
     * to_user_id отправленных лсок в порядке отправки. dm_sent-эхо не содержит to_user_id
     * (бэк отдаёт только from_user_id = мы сами), поэтому единственный способ понять какому
     * диалогу принадлежит эхо - FIFO-порядок в рамках одного dm-сокета.
     */
    private final ConcurrentLinkedDeque<Integer> pendingDmTargets = new ConcurrentLinkedDeque<>();

    // жизненный цикл

    /** запускает подключение в фоне сразу при заходе в клиент. */
    public void start() {
        scheduler.execute(this::connect);
    }

    /** кнопка повторить на экране ошибки/бана. */
    public void retry() {
        if (status == Status.CONNECTING) return;
        scheduler.execute(this::connect);
    }

    /** кнопка "отключиться" - глушит все сокеты и не реконнектит сама. */
    public void disconnect() {
        status = Status.DISCONNECTED;
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (chatSocket != null) chatSocket.stop();
        if (dmSocket != null) dmSocket.stop();
        if (clientChatSocket != null) clientChatSocket.stop();
        token = null;
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

            // при каждом (пере)подключении - чистый лист. кэш картинок сообщений (ImageCache)
            // сюда не входит - это отдельное требование ("кроме картинок")
            clearLocalState();

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
            if (optBool(login, "is_banned", false)) {
                status = Status.BANNED;
                return;
            }
            token = login.get("session_token").getAsString();

            loadSelf();
            loadFriends();
            loadPendingRequests();
            loadDialogs();
            loadGeneralHistory();
            loadClientChatHistory();

            openSockets();
            // ждём реального подключения сокетов (не просто вызова .start()) - иначе экран
            // "Подключение..." закрывается раньше, чем dm-сокет реально готов принимать сообщения,
            // и первая же отправка уходит в ещё не подключённый сокет
            awaitSocketsReady();

            if (status == Status.ONLINE) {
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
            }
        } catch (Throwable t) {
            fail(t.getMessage());
        }
    }

    private void clearLocalState() {
        synchronized (general.messages) {
            general.messages.clear();
        }
        general.pendingSent.clear();
        general.historyLoaded = false;
        general.hasMoreHistory = true;
        general.loadingMore = false;
        general.unread = 0;
        general.onlineCount = -1;

        synchronized (clientChat.messages) {
            clientChat.messages.clear();
        }
        clientChat.pendingSent.clear();
        clientChat.historyLoaded = false;
        clientChat.hasMoreHistory = true;
        clientChat.loadingMore = false;
        clientChat.unread = 0;
        clientChat.onlineCount = -1;

        // ВАЖНО: dms НЕ очищаем целиком (dms.clear() пересоздаёт Conversation по новой при
        // следующем dmFor()) - MessengerScreen.selected может в этот момент держать ссылку
        // на старый объект диалога. Если пересоздать карту, объект в selected становится
        // "осиротевшим": сообщения туда продолжают падать оптимистично, а дозаполнение по
        // dm_sent/dm ищет диалог уже в новом (другом) объекте - сообщение зависает в "..." навечно.
        // поэтому чистим состояние существующих диалогов на месте, identity сохраняется.
        for (Conversation conv : dms.values()) {
            synchronized (conv.messages) {
                conv.messages.clear();
            }
            conv.pendingSent.clear();
            conv.historyLoaded = false;
            conv.hasMoreHistory = true;
            conv.loadingMore = false;
            conv.unread = 0;
        }
        synchronized (friends) {
            friends.clear();
        }
        pendingDmTargets.clear();
        searchResults = List.of();
        pendingRequests = List.of();
        self = null;

        // аватарки чистим (могли остаться от прошлого аккаунта); картинки сообщений - нет
        AvatarRenderer.clearCache();
    }

    private void fail(String why) {
        System.out.println("[meadow-net] connect failed: " + why);
        status = Status.FAILED;
    }

    private void openSockets() {
        if (chatSocket != null) chatSocket.stop();
        if (dmSocket != null) dmSocket.stop();
        if (clientChatSocket != null) clientChatSocket.stop();

        chatSocket = new MeadowSocket("chat", http, scheduler,
                () -> token == null ? null : MeadowEndpoint.wsBase() + "/ws/chat?token=" + token,
                this::onChatFrame, this::onSocketStateChanged, this::onSocketClosed);
        dmSocket = new MeadowSocket("dm", http, scheduler,
                () -> token == null ? null : MeadowEndpoint.wsBase() + "/dm/ws/dm?token=" + token,
                this::onDmFrame, this::onSocketStateChanged, this::onSocketClosed);
        clientChatSocket = new MeadowSocket("clientchat", http, scheduler,
                () -> token == null ? null : MeadowEndpoint.wsBase() + "/ws/clientchat?token=" + token,
                this::onClientChatFrame, this::onSocketStateChanged, this::onSocketClosed);
        chatSocket.start();
        dmSocket.start();
        clientChatSocket.start();
    }

    /**
     * блокирует (сам) фоновый поток connect() пока все три сокета либо не подключатся, либо не
     * "устаканятся" (кто-то дал окончательный отказ - исчерпал ретраи/забанен/вытеснен). так UI не
     * покажет ONLINE раньше, чем реально можно слать сообщения. жёсткий дедлайн - подстраховка от
     * зависания на экране подключения, если что-то пошло совсем не так.
     */
    private void awaitSocketsReady() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (chatSocket.isConnected() && dmSocket.isConnected() && clientChatSocket.isConnected()) {
                status = Status.ONLINE;
                return;
            }
            if (isSettled(chatSocket) && isSettled(dmSocket) && isSettled(clientChatSocket)) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        boolean anyConnected = chatSocket.isConnected() || dmSocket.isConnected() || clientChatSocket.isConnected();
        status = anyConnected ? Status.ONLINE : Status.FAILED;
    }

    private static boolean isSettled(MeadowSocket s) {
        return s.isConnected() || s.isGivenUp();
    }

    private void onSocketStateChanged(boolean connected) {
        // Если все сокеты умерли после исчерпания реконнектов - показываем ошибку.
        boolean anyAlive = (chatSocket != null && chatSocket.isConnected())
                || (dmSocket != null && dmSocket.isConnected())
                || (clientChatSocket != null && clientChatSocket.isConnected());
        if (!anyAlive && status == Status.ONLINE) {
            status = Status.FAILED;
        }
    }

    /** код 4003 = бан (сервер шлёт его и на логине-в-процессе, и на кике админом). не реконнектим сами. */
    private void onSocketClosed(int code, String reason) {
        if (code != 4003 || status == Status.DISCONNECTED) return;
        if (chatSocket != null) chatSocket.stop();
        if (dmSocket != null) dmSocket.stop();
        if (clientChatSocket != null) clientChatSocket.stop();
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        status = Status.BANNED;
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
        if (u.clientName != null) {
            clientChat.title = u.clientName + " чат";
        }
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

    private void loadPendingRequests() {
        JsonObject res = getJson("/friends/pending");
        if (res == null || !res.has("pending")) return;
        List<PendingRequest> list = new ArrayList<>();
        for (JsonElement el : res.getAsJsonArray("pending")) {
            JsonObject p = el.getAsJsonObject();
            list.add(new PendingRequest(optInt(p, "user_id", 0), optStr(p, "username", ""),
                    optStr(p, "avatar_url", null), optStr(p, "client_name", null), optStr(p, "client_logo", null)));
        }
        pendingRequests = list;
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

    private static final int HISTORY_PAGE = 50;

    private void loadGeneralHistory() {
        JsonObject res = getJson("/chat/messages?channel=general&limit=" + HISTORY_PAGE);
        if (res == null || !res.has("messages")) return;
        synchronized (general.messages) {
            general.messages.clear();
        }
        JsonArray arr = res.getAsJsonArray("messages");
        for (JsonElement el : arr) {
            JsonObject m = el.getAsJsonObject();
            general.addMessage(historyMessage(m, general));
        }
        general.historyLoaded = true;
        general.hasMoreHistory = arr.size() >= HISTORY_PAGE;
    }

    private void loadClientChatHistory() {
        // бэк сам маппит алиас "client_chat" на реальное имя канала (client_slug_id_chat)
        JsonObject res = getJson("/chat/messages?channel=client_chat&limit=" + HISTORY_PAGE);
        if (res == null || !res.has("messages")) return;
        synchronized (clientChat.messages) {
            clientChat.messages.clear();
        }
        JsonArray arr = res.getAsJsonArray("messages");
        for (JsonElement el : arr) {
            JsonObject m = el.getAsJsonObject();
            clientChat.addMessage(historyMessage(m, clientChat));
        }
        clientChat.historyLoaded = true;
        clientChat.hasMoreHistory = arr.size() >= HISTORY_PAGE;
    }

    private Message historyMessage(JsonObject m, Conversation conv) {
        boolean mine = self != null && optStr(m, "username", "").equals(self.username)
                && nullSafeEq(optStr(m, "client_slug", null), self.clientSlug);
        Message msg = new Message(optLong(m, "id", 0), optStr(m, "username", "?"),
                optStr(m, "client_logo", null), optStr(m, "client_slug", null),
                optStr(m, "text", ""), mine);
        msg.imageUrl = optStr(m, "image_url", null);
        msg.senderUserId = optInt(m, "user_id", 0);
        // если API отдаёт avatar_url в сообщении — берём его; иначе для своих — self
        String frameAvatar = optStr(m, "avatar_url", null);
        msg.avatarUrl = frameAvatar != null ? frameAvatar : (mine ? selfAvatar() : null);
        Instant serverTs = Message.parseTimestamp(optStr(m, "timestamp", null));
        if (serverTs != null) msg.applyServerTime(serverTs);
        return msg;
    }

    private Message dmHistoryMessage(JsonObject m, Conversation conv) {
        boolean mine = self != null && optInt(m, "sender_id", 0) == self.id;
        Message msg = new Message(optLong(m, "id", 0),
                optStr(m, "sender_username", mine ? safeSelfName() : conv.title),
                mine ? selfLogo() : conv.clientLogo, null, optStr(m, "text", ""), mine);
        msg.imageUrl = optStr(m, "image_url", null);
        msg.senderUserId = mine ? (self != null ? self.id : 0) : optInt(m, "sender_id", conv.userId);
        String senderAvatar = optStr(m, "sender_avatar_url", null);
        msg.avatarUrl = senderAvatar != null ? senderAvatar : (mine ? selfAvatar() : conv.avatarUrl);
        Instant serverTs = Message.parseTimestamp(optStr(m, "timestamp", null));
        if (serverTs != null) msg.applyServerTime(serverTs);
        return msg;
    }

    /** ленивая подгрузка истории лсок при открытии диалога. */
    public void ensureHistory(Conversation conv) {
        if (conv == null || conv.historyLoaded || conv.type != Conversation.Type.DM) return;
        conv.historyLoaded = true; // не дёргаем повторно даже при пустом ответе
        scheduler.execute(() -> {
            JsonObject res = getJson("/dm/" + conv.userId + "?limit=" + HISTORY_PAGE);
            if (res == null || !res.has("messages")) return;
            JsonArray arr = res.getAsJsonArray("messages");
            for (JsonElement el : arr) {
                conv.addMessage(dmHistoryMessage(el.getAsJsonObject(), conv));
            }
            conv.hasMoreHistory = arr.size() >= HISTORY_PAGE;
        });
    }

    /**
     * подгружает более старую страницу истории (before_id = id самого старого уже загруженного
     * сообщения) - дёргается экраном, когда юзер долистал прокрутку до верха.
     */
    public void loadMoreHistory(Conversation conv) {
        if (conv == null || conv.loadingMore || !conv.hasMoreHistory) return;
        long beforeId;
        synchronized (conv.messages) {
            if (conv.messages.isEmpty()) return;
            beforeId = conv.messages.peekFirst().id;
        }
        if (beforeId <= 0) return; // самое старое сообщение - ещё неподтверждённое локальное, ждём его id
        conv.loadingMore = true;
        scheduler.execute(() -> {
            try {
                String path = conv.type == Conversation.Type.DM
                        ? "/dm/" + conv.userId + "?limit=" + HISTORY_PAGE + "&before_id=" + beforeId
                        : "/chat/messages?channel=" + conv.channel + "&limit=" + HISTORY_PAGE + "&before_id=" + beforeId;
                JsonObject res = getJson(path);
                if (res == null || !res.has("messages")) {
                    conv.hasMoreHistory = false;
                    return;
                }
                JsonArray arr = res.getAsJsonArray("messages");
                if (arr.isEmpty()) {
                    conv.hasMoreHistory = false;
                    return;
                }
                List<Message> older = new ArrayList<>();
                for (JsonElement el : arr) {
                    JsonObject m = el.getAsJsonObject();
                    older.add(conv.type == Conversation.Type.DM ? dmHistoryMessage(m, conv) : historyMessage(m, conv));
                }
                conv.addOlderMessages(older);
                conv.hasMoreHistory = arr.size() >= HISTORY_PAGE;
            } finally {
                conv.loadingMore = false;
            }
        });
    }

    // входящие вебсокет-фреймы

    private void onChatFrame(JsonObject d) {
        String op = optStr(d, "op", "");
        switch (op) {
            case "message" -> handleChannelMessage(general, d);
            // /notify - тот же бродкаст что и обычное сообщение (та же форма, тот же pendingSent
            // у отправителя), плюс HUD-тост для всех получателей
            case "message_notify" -> {
                handleChannelMessage(general, d);
                notifyMessageNotify(d);
            }
            // онлайн счётчик канала шлётся только в user_joined/user_left (включая своё подключение)
            case "user_joined", "user_left" -> {
                if (d.has("online")) {
                    general.onlineCount = optInt(d, "online", general.onlineCount);
                }
            }
            case "action_denied" -> notifyActionDenied(general, d);
            default -> {
            }
        }
    }

    private void onClientChatFrame(JsonObject d) {
        String op = optStr(d, "op", "");
        switch (op) {
            case "message" -> handleChannelMessage(clientChat, d);
            case "message_notify" -> {
                handleChannelMessage(clientChat, d);
                notifyMessageNotify(d);
            }
            case "user_joined", "user_left" -> {
                if (d.has("online")) {
                    clientChat.onlineCount = optInt(d, "online", clientChat.onlineCount);
                }
            }
            case "action_denied" -> notifyActionDenied(clientChat, d);
            default -> {
            }
        }
    }

    /** общая обработка op:"message" для general/clientChat - своё эхо дозаполняет pendingSent, чужое добавляется как новое. */
    private void handleChannelMessage(Conversation conv, JsonObject d) {
        boolean mine = self != null && optStr(d, "username", "").equals(self.username)
                && nullSafeEq(optStr(d, "client_slug", null), self.clientSlug);
        Instant ts = Message.parseTimestamp(optStr(d, "timestamp", null));

        if (mine) {
            Message pending = conv.pendingSent.pollFirst();
            if (pending != null) {
                // подтверждение от сервера - подменяем контент на то, что реально ушло в чат
                // (сервер мог поджать/поправить текст), а не на то, что мы локально считали отправленным
                pending.id = optLong(d, "id", 0);
                pending.text = optStr(d, "text", pending.text);
                pending.username = optStr(d, "username", pending.username);
                pending.clientLogo = optStr(d, "client_logo", pending.clientLogo);
                pending.clientSlug = optStr(d, "client_slug", pending.clientSlug);
                pending.senderUserId = optInt(d, "user_id", pending.senderUserId);
                pending.imageUrl = optStr(d, "image_url", pending.imageUrl);
                pending.localPreviewPath = null;
                pending.sendState = Message.SendState.SENT;
                // аватарку намеренно не берём из кадра - она может быть устаревшей (см. комментарий ниже)
                if (ts != null) pending.applyServerTime(ts);
                return;
            }
        }

        Message msg = new Message(optLong(d, "id", 0), optStr(d, "username", "?"),
                optStr(d, "client_logo", null), optStr(d, "client_slug", null),
                optStr(d, "text", ""), mine);
        msg.imageUrl = optStr(d, "image_url", null);
        msg.senderUserId = optInt(d, "user_id", 0);
        // своя аватарка — всегда из актуального селфа, не из (потенциально
        // устаревшего, кэшированного сервером на момент открытия WS) фреймва
        msg.avatarUrl = mine ? selfAvatar() : optStr(d, "avatar_url", null);
        if (ts != null) msg.applyServerTime(ts);
        conv.addMessage(msg);
        if (!mine && !isViewing(conv)) {
            conv.unread++;
        }
    }

    /**
     * conv может быть null (для лс он ищется по to_user_id из самого фрейма). Резолвит "зависшее"
     * оптимистичное сообщение как FAILED - иначе оно навечно застревает в pendingSent и следующее
     * реальное подтверждение (op:"message"/dm_sent) по FIFO ошибочно применяется не к тому сообщению.
     */
    private void notifyActionDenied(Conversation conv, JsonObject d) {
        // общий чат/clientchat шлют "message", лс - "detail"
        String reason = optStr(d, "message", optStr(d, "detail", "Неизвестная причина"));
        notify(Text.literal("Действие отклонено"), Text.literal(reason));
        if (conv != null) {
            Message pending = conv.pendingSent.pollFirst();
            if (pending != null) {
                pending.sendState = Message.SendState.FAILED;
            }
        }
    }

    /** op:"message_notify" - та же форма фрейма что у обычного сообщения (username/text). */
    private void notifyMessageNotify(JsonObject d) {
        String sender = optStr(d, "username", "?");
        String message = optStr(d, "text", "");
        notify(Text.literal("Новое оповещение"), Text.literal(sender + ": " + message));
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
                incoming.imageUrl = optStr(d, "image_url", null);
                incoming.senderUserId = fromId;
                Instant ts = Message.parseTimestamp(optStr(d, "timestamp", null));
                if (ts != null) incoming.applyServerTime(ts);
                conv.addMessage(incoming);
                if (!isViewing(conv)) {
                    conv.unread++;
                    notify(Text.literal(username), Text.literal("Новое сообщение: " + text));
                }
            }
            case "dm_sent" -> {
                Integer target = pendingDmTargets.pollFirst();
                if (target == null) return;
                Conversation conv = dms.get(target);
                if (conv == null) return;
                Instant ts = Message.parseTimestamp(optStr(d, "timestamp", null));
                Message pending = conv.pendingSent.pollFirst();
                if (pending != null) {
                    pending.id = optLong(d, "id", 0);
                    pending.imageUrl = optStr(d, "image_url", pending.imageUrl);
                    pending.localPreviewPath = null;
                    pending.sendState = Message.SendState.SENT;
                    if (ts != null) pending.applyServerTime(ts);
                } else {
                    Message sent = new Message(optLong(d, "id", 0), safeSelfName(),
                            selfLogo(), self != null ? self.clientSlug : null,
                            optStr(d, "text", ""), true);
                    // своя аватарка — всегда из актуального self (обновляется поллингом),
                    // а не из кадра: сервер шлёт её из кэша, сделанного при открытии WS
                    sent.avatarUrl = selfAvatar();
                    sent.imageUrl = optStr(d, "image_url", null);
                    if (ts != null) sent.applyServerTime(ts);
                    conv.addMessage(sent);
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
            case "friend_request" -> {
                notify(Text.literal("Новая заявка в друзья"),
                        Text.literal(optStr(d, "username", "Игрок") + " отправил вам заявку в друзья"));
                scheduler.execute(this::loadPendingRequests);
            }
            case "friend_added" -> {
                notify(Text.literal("Заявка в друзья принята"),
                        Text.literal(optStr(d, "username", "Игрок") + " принял вашу заявку в друзья!"));
                scheduler.execute(this::loadFriends);
            }
            case "action_denied" -> notifyActionDenied(dms.get(optInt(d, "to_user_id", 0)), d);
            case "message_notify" -> notifyMessageNotify(d);
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

    /** отправить текстовое сообщение (без картинки) в открытый диалог (канал или ЛС). */
    public void sendMessage(Conversation conv, String text) {
        sendMessage(conv, text, null);
    }

    /** отправить сообщение (текст и/или уже загруженная картинка) в открытый диалог. добавляется оптимистично сразу. */
    public void sendMessage(Conversation conv, String text, String imageUrl) {
        if (conv == null) return;
        String trimmed = text == null ? "" : text.trim();
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        if (trimmed.isEmpty() && !hasImage) return;

        Message local = new Message(0, safeSelfName(), selfLogo(),
                self != null ? self.clientSlug : null, trimmed, true);
        local.avatarUrl = selfAvatar();
        local.senderUserId = self != null ? self.id : 0;
        if (hasImage) local.imageUrl = imageUrl;
        local.sendState = Message.SendState.SENDING;
        conv.addMessage(local);
        conv.pendingSent.addLast(local);

        dispatchSend(conv, local, trimmed, imageUrl);
    }

    /**
     * отправить картинку с диска: сообщение появляется в чате сразу (UPLOADING), картинка
     * грузится на сервер в фоне, и только после получения image_url реально уходит по WS.
     */
    public void sendImageMessage(Conversation conv, String text, File file) {
        if (conv == null || file == null) return;
        String trimmed = text == null ? "" : text.trim();

        Message local = new Message(0, safeSelfName(), selfLogo(),
                self != null ? self.clientSlug : null, trimmed, true);
        local.avatarUrl = selfAvatar();
        local.senderUserId = self != null ? self.id : 0;
        local.localPreviewPath = file.getAbsolutePath();
        local.sendState = Message.SendState.UPLOADING;
        conv.addMessage(local);

        scheduler.execute(() -> {
            byte[] data;
            try {
                data = Files.readAllBytes(file.toPath());
            } catch (Throwable t) {
                local.sendState = Message.SendState.FAILED;
                return;
            }
            uploadImage(data, file.getName(), guessContentType(file.getName())).whenComplete((url, err) -> {
                if (err != null || url == null) {
                    local.sendState = Message.SendState.FAILED;
                    return;
                }
                local.sendState = Message.SendState.SENDING;
                conv.pendingSent.addLast(local);
                dispatchSend(conv, local, trimmed, url);
            });
        });
    }

    private void dispatchSend(Conversation conv, Message local, String text, String imageUrl) {
        JsonObject p = new JsonObject();
        p.addProperty("op", "message");
        if (!text.isEmpty()) p.addProperty("text", text);
        if (imageUrl != null && !imageUrl.isBlank()) p.addProperty("image_url", imageUrl);

        MeadowSocket socket = socketFor(conv);
        if (conv.type == Conversation.Type.DM) {
            p.addProperty("to_user_id", conv.userId);
            pendingDmTargets.addLast(conv.userId);
        }
        if (socket != null && socket.isConnected()) {
            socket.send(p);
        } else {
            local.sendState = Message.SendState.FAILED;
            conv.pendingSent.remove(local);
            if (conv.type == Conversation.Type.DM) pendingDmTargets.removeLastOccurrence(conv.userId);
        }
    }

    private MeadowSocket socketFor(Conversation conv) {
        if (conv.type == Conversation.Type.DM) return dmSocket;
        if ("client_chat".equals(conv.channel)) return clientChatSocket;
        return chatSocket;
    }

    private static String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
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

    // друзья / заявки / поиск

    /** поиск юзеров по нику - результат в getSearchResults(), бэк сам режет лимитом. */
    public void searchUsers(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            searchResults = List.of();
            return;
        }
        scheduler.execute(() -> {
            JsonObject res = getJson("/users/search?q=" + urlEncode(q) + "&limit=7");
            if (res == null || !res.has("users")) return;
            List<NetUser> list = new ArrayList<>();
            for (JsonElement el : res.getAsJsonArray("users")) {
                JsonObject u = el.getAsJsonObject();
                NetUser nu = new NetUser();
                nu.id = optInt(u, "id", 0);
                nu.username = optStr(u, "username", "");
                nu.avatarUrl = optStr(u, "avatar_url", null);
                nu.clientName = optStr(u, "client_name", null);
                nu.clientSlug = optStr(u, "client_slug", null);
                nu.clientLogo = optStr(u, "client_logo", null);
                // да, у бэка опечатка в имени поля - "sended", не "sent"
                nu.requestSent = optBool(u, "request_sended", false);
                list.add(nu);
            }
            searchResults = list;
        });
    }

    public void sendFriendRequest(int userId) {
        // сразу гасим плюсик в поиске - не ждём пока юзер перенаберёт запрос и придёт свежий request_sent
        for (NetUser u : searchResults) {
            if (u.id == userId) u.requestSent = true;
        }
        scheduler.execute(() -> postJson("/friends/request/" + userId, new JsonObject(), true));
    }

    public void acceptFriendRequest(int userId) {
        scheduler.execute(() -> {
            postJson("/friends/accept/" + userId, new JsonObject(), true);
            loadFriends();
            loadPendingRequests();
        });
    }

    public void declineFriendRequest(int userId) {
        scheduler.execute(() -> {
            postJson("/friends/decline/" + userId, new JsonObject(), true);
            loadPendingRequests();
        });
    }

    public void removeFriend(int userId) {
        scheduler.execute(() -> {
            deleteJson("/friends/" + userId);
            loadFriends();
        });
    }

    /**
     * пожаловаться на конкретное сообщение (context_type "chat"/"dm") - текст жалобы бэк сам берёт
     * из сохранённого сообщения по context_id, comment - необязательный комментарий репортера.
     * reportedUserId<=0 (бэк ещё не проставил user_id) или contextId<=0 - тихо игнор.
     */
    public void reportMessage(int reportedUserId, String comment, String contextType, long contextId) {
        if (reportedUserId <= 0 || contextId <= 0) return;
        scheduler.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("reported_user_id", reportedUserId);
            body.addProperty("context_type", contextType);
            body.addProperty("context_id", contextId);
            if (comment != null && !comment.isBlank()) body.addProperty("comment", comment.trim());
            postJson("/report/", body, true);
        });
    }

    private CompletableFuture<String> uploadImage(byte[] data, String filename, String contentType) {
        String boundary = "----meadow" + Long.toHexString(System.nanoTime());
        byte[] body = MultipartBodyPublisher.single(boundary, "file", filename, contentType, data);
        HttpRequest req = HttpRequest.newBuilder(URI.create(MeadowEndpoint.BASE_URL + "/upload/image"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() / 100 != 2) {
                System.out.println("[meadow-net] upload -> " + resp.statusCode() + " " + resp.body());
                return null;
            }
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            return o.has("image_url") ? o.get("image_url").getAsString() : null;
        });
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

    public Conversation getClientChat() {
        return clientChat;
    }

    public List<Friend> getFriends() {
        synchronized (friends) {
            return new ArrayList<>(friends);
        }
    }

    public List<NetUser> getSearchResults() {
        return searchResults;
    }

    public List<PendingRequest> getPendingRequests() {
        return pendingRequests;
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

    private JsonObject deleteJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(MeadowEndpoint.BASE_URL + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .DELETE().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.out.println("[meadow-net] DELETE " + path + " -> " + resp.statusCode());
                return null;
            }
            if (resp.body() == null || resp.body().isBlank()) return new JsonObject();
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Throwable t) {
            System.out.println("[meadow-net] DELETE " + path + " err: " + t.getMessage());
            return null;
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        // MySQL-драйвер иногда отдаёт boolean-поля как 0/1 (число), а не true/false - Gson же
        // на числах Boolean.parseBoolean("1") молча вернёт false. поэтому число проверяем отдельно
        com.google.gson.JsonPrimitive p = o.get(k).getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsInt() != 0;
        return Boolean.parseBoolean(p.getAsString());
    }

    private static boolean nullSafeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}

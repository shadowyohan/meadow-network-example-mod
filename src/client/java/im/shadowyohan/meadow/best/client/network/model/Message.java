package im.shadowyohan.meadow.best.client.network.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

// одно сообщение (чат или лс) как его рисует мессенджер
public final class Message {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    // статус локально отправленного (ещё не подтверждённого сервером) сообщения
    public enum SendState {UPLOADING, SENDING, SENT, FAILED}

    public long id;
    public String username;
    public String clientName;
    public String clientSlug;
    public String clientLogo;
    public String avatarUrl;   // аватарка отправителя
    public int senderUserId;   // 0 - неизвестно (бэк ещё не отдаёт user_id в общих чатах)
    public String text;
    public String imageUrl;         // /uploads/... после подтверждения сервером
    public String localPreviewPath; // локальный путь к файлу, пока картинка аплоадится
    public String time;        // "HH:mm" под пузырём
    public boolean mine;       // наше -> рисуется справа
    public volatile SendState sendState = SendState.SENT;

    // серверный таймштамп, если есть - иначе фоллбэк на клиентское время в момент создания объекта
    public volatile Instant serverTime;

    public Message(long id, String username, String clientLogo, String clientSlug, String text, boolean mine) {
        this.id = id;
        this.username = username;
        this.clientLogo = clientLogo;
        this.clientSlug = clientSlug;
        this.text = text;
        this.mine = mine;
        this.serverTime = Instant.now();
        this.time = formatTime(this.serverTime);
    }

    public void applyServerTime(Instant instant) {
        if (instant == null) return;
        this.serverTime = instant;
        this.time = formatTime(instant);
    }

    private static String formatTime(Instant instant) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        return zdt.format(HM);
    }

    // парсит таймштамп бэка (ISO 8601, "...+00:00") - null если не пришёл/битый
    public static Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}

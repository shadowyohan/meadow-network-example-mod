package im.shadowyohan.meadow.best.client.network.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// одно сообщение (чат или лс) как его рисует мессенджер
public final class Message {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public long id;
    public String username;
    public String clientName;
    public String clientSlug;
    public String clientLogo;
    public String avatarUrl;   // аватарка отправителя
    public String text;
    public String time;        // "HH:mm" под пузырём
    public boolean mine;       // наше -> рисуется справа

    public Message(long id, String username, String clientLogo, String clientSlug, String text, boolean mine) {
        this.id = id;
        this.username = username;
        this.clientLogo = clientLogo;
        this.clientSlug = clientSlug;
        this.text = text;
        this.mine = mine;
        this.time = LocalTime.now().format(HM);
    }
}

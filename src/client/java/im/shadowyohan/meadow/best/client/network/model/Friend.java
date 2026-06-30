package im.shadowyohan.meadow.best.client.network.model;

// друг из GET /friends/ (для списка "личные" в сайдбаре)
public final class Friend {
    public int id;
    public String username;
    public String avatarUrl;
    public String clientName;
    public String clientLogo;
    public volatile boolean online;

    public Friend(int id, String username, String avatarUrl, String clientName, String clientLogo) {
        this.id = id;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.clientName = clientName;
        this.clientLogo = clientLogo;
    }
}

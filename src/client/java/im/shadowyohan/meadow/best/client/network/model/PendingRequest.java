package im.shadowyohan.meadow.best.client.network.model;

// входящая заявка в друзья из GET /friends/pending
public final class PendingRequest {
    public int userId;
    public String username;
    public String avatarUrl;
    public String clientName;
    public String clientLogo;

    public PendingRequest(int userId, String username, String avatarUrl, String clientName, String clientLogo) {
        this.userId = userId;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.clientName = clientName;
        this.clientLogo = clientLogo;
    }
}

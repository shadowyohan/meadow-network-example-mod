package im.shadowyohan.meadow.best.client.network.model;

// профиль текущего юзера (GET /users/me)
public final class NetUser {
    public int id;
    public String username;
    public String avatarUrl;
    public String clientName;
    public String clientSlug;
    public String clientLogo;
    // true - заявка в друзья уже отправлена (только для результатов /users/search)
    public boolean requestSent;
}

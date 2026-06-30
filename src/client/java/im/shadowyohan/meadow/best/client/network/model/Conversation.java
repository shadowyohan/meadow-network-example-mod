package im.shadowyohan.meadow.best.client.network.model;

import java.util.ArrayDeque;
import java.util.Deque;

// ветка в сайдбаре - либо канал общего чата (CHANNEL), либо лс (DM)
public final class Conversation {

    public enum Type {CHANNEL, DM}

    public final Type type;
    public final String channel;  // для CHANNEL, иначе null
    public final int userId;      // для DM, иначе -1

    public String title;
    public String clientLogo;     // лого визуала собеседника
    public String avatarUrl;      // аватарка собеседника / иконка канала
    public volatile boolean online;
    public volatile int unread;
    // только CHANNEL: онлайн в канале (-1 пока неизвестно), приходит из ws user_joined/user_left
    public volatile int onlineCount = -1;
    public volatile boolean historyLoaded;

    // старые -> новые, синхронизация по самому списку
    public final Deque<Message> messages = new ArrayDeque<>();

    private Conversation(Type type, String channel, int userId, String title) {
        this.type = type;
        this.channel = channel;
        this.userId = userId;
        this.title = title;
    }

    public static Conversation channel(String channel, String title) {
        return new Conversation(Type.CHANNEL, channel, -1, title);
    }

    public static Conversation dm(int userId, String title) {
        return new Conversation(Type.DM, null, userId, title);
    }

    // ключ для карты диалогов и сравнения "открыт ли этот диалог"
    public String key() {
        return type == Type.CHANNEL ? "ch:" + channel : "dm:" + userId;
    }

    public void addMessage(Message message) {
        synchronized (messages) {
            // защита от дублей по id (эхо dm_sent + возможная история)
            if (message.id > 0) {
                for (Message m : messages) {
                    if (m.id == message.id) return;
                }
            }
            messages.addLast(message);
            while (messages.size() > 200) {
                messages.removeFirst();
            }
        }
    }
}

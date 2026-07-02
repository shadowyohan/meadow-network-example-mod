package im.shadowyohan.meadow.best.client.network;

import java.util.concurrent.ConcurrentLinkedQueue;

/** очередь путей, накинутых на окно через GLFW drop callback - забирает MessengerScreen, пока он открыт. */
public final class DroppedFiles {

    private DroppedFiles() {
    }

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();

    public static void push(String path) {
        QUEUE.add(path);
    }

    /** следующий закинутый путь или null - вызывающий сам решает подходит ли под картинку. */
    public static String poll() {
        return QUEUE.poll();
    }
}

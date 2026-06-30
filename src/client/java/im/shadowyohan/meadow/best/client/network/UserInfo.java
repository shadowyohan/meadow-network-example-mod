package im.shadowyohan.meadow.best.client.network;

import net.minecraft.client.MinecraftClient;

/**
 * Личность пользователя для входа в meadow!network.
 *
 * Здесь я поставил обычные статичные значения.
 * Вам необходимо интрегивоать это со своей протой, и подставлять username и UID из проты (пример: shadow, 1).
 */

public final class UserInfo {

    private UserInfo() {
    }

    public static volatile String uid = "your-uid (цифра), должен быть string";
    public static volatile String username = "your-username";

}

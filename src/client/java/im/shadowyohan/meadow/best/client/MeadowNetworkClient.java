package im.shadowyohan.meadow.best.client;

import im.shadowyohan.meadow.best.client.network.NetworkManager;
import im.shadowyohan.meadow.best.client.screen.MessengerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class MeadowNetworkClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // коннектимся к сети в фоне сразу на старте
        NetworkManager.get().start();

        // кейбинд открытия мессенджера (дефолт - M, категория "разное")
        KeyBinding openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.meadow-network.messenger",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "key.categories.misc"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MessengerScreen());
                }
            }
        });
    }
}

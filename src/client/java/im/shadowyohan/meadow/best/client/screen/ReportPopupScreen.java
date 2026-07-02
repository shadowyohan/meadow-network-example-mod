package im.shadowyohan.meadow.best.client.screen;

import im.shadowyohan.meadow.best.client.network.NetworkManager;
import im.shadowyohan.meadow.best.client.network.model.Conversation;
import im.shadowyohan.meadow.best.client.network.model.Message;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

// маленький модал по ПКМ на сообщении - жалоба через POST /report/
public class ReportPopupScreen extends Screen {

    private static final int W = 210;
    private static final int H = 92;

    private final Screen parent;
    private final Message message;
    private final Conversation conv;

    private TextFieldWidget reasonField;
    private int boxX, boxY;
    private int sendBtnX, sendBtnY, sendBtnW, sendBtnH;
    private boolean sent;

    public ReportPopupScreen(Screen parent, Message message, Conversation conv) {
        super(Text.literal("Пожаловаться"));
        this.parent = parent;
        this.message = message;
        this.conv = conv;
    }

    private NetworkManager net() {
        return NetworkManager.get();
    }

    @Override
    protected void init() {
        boxX = (this.width - W) / 2;
        boxY = (this.height - H) / 2;

        reasonField = new TextFieldWidget(this.textRenderer, boxX + 8, boxY + 32, W - 16, 16, Text.literal("Комментарий (необязательно)"));
        reasonField.setMaxLength(512);
        addSelectableChild(reasonField);
        reasonField.setFocused(true);
        setFocused(reasonField);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(boxX, boxY, boxX + W, boxY + H, 0xE0000000);
        ctx.fill(boxX, boxY, boxX + W, boxY + 1, 0x26FFFFFF);
        ctx.fill(boxX, boxY + H - 1, boxX + W, boxY + H, 0x26FFFFFF);
        ctx.fill(boxX, boxY, boxX + 1, boxY + H, 0x26FFFFFF);
        ctx.fill(boxX + W - 1, boxY, boxX + W, boxY + H, 0x26FFFFFF);

        String title = "Пожаловаться на " + message.username;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(title, W - 16), boxX + 8, boxY + 8, 0xFFFFFFFF, false);

        boolean canReport = message.senderUserId > 0;
        if (!canReport) {
            String warn = "Нет данных об отправителе, репорт недоступен";
            ctx.drawText(textRenderer, warn, boxX + 8, boxY + 20, 0xFFFF6767, false);
        } else {
            reasonField.render(ctx, mouseX, mouseY, delta);
        }

        sendBtnW = 100;
        sendBtnH = 16;
        sendBtnX = boxX + (W - sendBtnW) / 2;
        sendBtnY = boxY + H - sendBtnH - 8;
        boolean hover = canReport && !sent && isHovered(mouseX, mouseY, sendBtnX, sendBtnY, sendBtnW, sendBtnH);
        int bg = !canReport || sent ? 0x40FFFFFF : (hover ? 0xE0FF6767 : 0xB0FF6767);
        ctx.fill(sendBtnX, sendBtnY, sendBtnX + sendBtnW, sendBtnY + sendBtnH, bg);
        String label = sent ? "Отправлено" : "Пожаловаться";
        ctx.drawText(textRenderer, label, sendBtnX + (sendBtnW - textRenderer.getWidth(label)) / 2,
                sendBtnY + (sendBtnH - textRenderer.fontHeight) / 2 + 1, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !sent && message.senderUserId > 0
                && isHovered(mouseX, mouseY, sendBtnX, sendBtnY, sendBtnW, sendBtnH)) {
            String contextType = conv != null && conv.type == Conversation.Type.DM ? "dm" : "chat";
            net().reportMessage(message.senderUserId, reasonField.getText(), contextType, message.id);
            sent = true;
            return true;
        }
        if (reasonField != null && reasonField.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (reasonField != null && reasonField.isFocused() && reasonField.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        if (reasonField != null && reasonField.isFocused() && reasonField.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private static boolean isHovered(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}

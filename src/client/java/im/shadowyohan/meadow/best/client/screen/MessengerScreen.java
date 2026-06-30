package im.shadowyohan.meadow.best.client.screen;

import im.shadowyohan.meadow.best.client.network.AvatarRenderer;
import im.shadowyohan.meadow.best.client.network.NetworkManager;
import im.shadowyohan.meadow.best.client.network.model.Conversation;
import im.shadowyohan.meadow.best.client.network.model.Message;
import im.shadowyohan.meadow.best.client.network.model.NetUser;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class MessengerScreen extends Screen {

    // ── размеры оболочки ──
    private static final int BOX_W = 440;
    private static final int BOX_H = 256;
    private static final int PAD = 6;
    private static final int SIDEBAR_W = 132;
    private static final int ROW_H = 18;

    // ── цвета (ARGB) ──
    private static final int BG = 0xD0000000;
    private static final int PANEL = 0x14FFFFFF;
    private static final int ITEM = 0x14FFFFFF;
    private static final int ITEM_HOVER = 0x24FFFFFF;
    private static final int ITEM_SELECTED = 0x3CFFFFFF;
    private static final int ITEM_EMPTY = 0x0CFFFFFF;
    private static final int LABEL_DIM = 0x66FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int GREEN = 0xFF79FF89;
    private static final int GRAY_DOT = 0x70FFFFFF;
    private static final int BUBBLE_IN = 0x18FFFFFF;
    private static final int BUBBLE_OUT = 0x33FFFFFF;
    private static final int TIME_COLOR = 0x66FFFFFF;
    private static final int SEND_BG = 0xB09D6CFF;
    private static final int SEND_BG_HOVER = 0xE09D6CFF;
    private static final int BRAND = 0xFFC4A7FF;
    private static final int ERROR = 0xFFFF6767;
    private static final int BORDER = 0x26FFFFFF;

    private int boxX, boxY;
    private boolean dragging;
    private int dragOffsetX, dragOffsetY;

    private TextFieldWidget input;
    private Conversation selected;

    // прокрутка сообщений, 0 — последние снизу, рост — вверх к истории
    private float scroll;
    private float maxScroll;

    // зоны кликов (заполняются при рендере)
    private final List<RowHit> rowHits = new ArrayList<>();
    private int sendBtnX, sendBtnY, sendBtnW, sendBtnH;
    private int retryBtnX, retryBtnY, retryBtnW, retryBtnH;

    private record RowHit(int x, int y, int w, int h, Conversation conv) {
    }

    public MessengerScreen() {
        super(Text.literal("meadow!messenger"));
    }

    private NetworkManager net() {
        return NetworkManager.get();
    }

    @Override
    protected void init() {
        boxX = (this.width - BOX_W) / 2;
        boxY = (this.height - BOX_H) / 2;

        input = new TextFieldWidget(this.textRenderer, 0, 0, 100, 14, Text.literal("Введите текст"));
        input.setMaxLength(512);
        input.setDrawsBackground(true);
        addSelectableChild(input);

        if (selected == null) {
            selected = net().getGeneral();
        }
        net().setViewing(selected);
        net().ensureHistory(selected);
    }

    @Override
    public void removed() {
        net().setViewing(null);
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        rowHits.clear();

        // оболочка
        ctx.fill(boxX, boxY, boxX + BOX_W, boxY + BOX_H, BG);
        drawBorder(ctx, boxX, boxY, BOX_W, BOX_H, BORDER);

        int sidebarX = boxX + PAD;
        int sidebarY = boxY + PAD;
        int sidebarH = BOX_H - PAD * 2;
        renderSidebar(ctx, sidebarX, sidebarY, SIDEBAR_W, sidebarH, mouseX, mouseY);

        int panelX = sidebarX + SIDEBAR_W + PAD;
        int panelY = boxY + PAD;
        int panelW = boxX + BOX_W - PAD - panelX;
        int panelH = BOX_H - PAD * 2;

        NetworkManager.Status status = net().getStatus();
        if (status == NetworkManager.Status.FAILED) {
            renderErrorPanel(ctx, panelX, panelY, panelW, panelH);
        } else if (status != NetworkManager.Status.ONLINE) {
            renderConnectingPanel(ctx, panelX, panelY, panelW, panelH);
        } else {
            renderChatPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
        }
    }

    // сайдбар

    private void renderSidebar(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ctx.fill(x, y, x + w, y + h, PANEL);

        int pad = PAD;
        int curX = x + pad;
        int curY = y + pad;
        int innerW = w - pad * 2;

        // лого-шапка
        int logoH = 20;
        ctx.fill(curX, curY, curX + innerW, curY + logoH, ITEM);
        String logo = "messenger!";
        ctx.drawText(textRenderer, logo, curX + (innerW - textRenderer.getWidth(logo)) / 2,
                curY + (logoH - textRenderer.fontHeight) / 2 + 1, BRAND, false);
        curY += logoH + 6;

        // секция чаты
        ctx.drawText(textRenderer, "чаты", curX, curY, LABEL_DIM, false);
        curY += textRenderer.fontHeight + 3;
        curY = drawRow(ctx, net().getGeneral(), curX, curY, innerW, mouseX, mouseY);

        curY += 3;
        // секция личные
        ctx.drawText(textRenderer, "личные", curX, curY, LABEL_DIM, false);
        curY += textRenderer.fontHeight + 3;

        List<Conversation> dms = net().getDirectConversations();
        if (dms.isEmpty()) {
            curY = drawEmptyRow(ctx, "Нет личных переписок", curX, curY, innerW);
        } else {
            for (Conversation conv : dms) {
                curY = drawRow(ctx, conv, curX, curY, innerW, mouseX, mouseY);
            }
        }

        // карточка текущего юзераы
        int bottomY = y + h - pad;
        NetUser self = net().getSelf();
        int userH = 20;
        bottomY -= userH;
        ctx.fill(curX, bottomY, curX + innerW, bottomY + userH, ITEM);
        int av = 14;
        AvatarRenderer.draw(ctx, self != null ? self.avatarUrl : null,
                self != null ? self.username : "?", curX + 3, bottomY + (userH - av) / 2, av);
        ctx.drawText(textRenderer, self != null ? self.username : "не в сети",
                curX + 3 + av + 4, bottomY + (userH - textRenderer.fontHeight) / 2 + 1, WHITE, false);

        bottomY -= 6;
        drawSmall(ctx, "working on meadow!network", curX, bottomY, LABEL_DIM, 0.7f);
    }

    // мелкий текст через scale матрицы
    private void drawSmall(DrawContext ctx, String text, int x, int y, int color, float scale) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawText(textRenderer, text, 0, 0, color, false);
        ctx.getMatrices().pop();
    }

    private int drawRow(DrawContext ctx, Conversation conv, int x, int y, int w, int mouseX, int mouseY) {
        boolean sel = selected != null && selected.key().equals(conv.key());
        boolean hover = isHovered(mouseX, mouseY, x, y, w, ROW_H);
        ctx.fill(x, y, x + w, y + ROW_H, sel ? ITEM_SELECTED : hover ? ITEM_HOVER : ITEM);

        int avatar = 14;
        AvatarRenderer.draw(ctx, conv.avatarUrl, conv.title, x + 3, y + (ROW_H - avatar) / 2, avatar);
        int tx = x + 3 + avatar + 4;
        String title = trim(conv.title, w - (avatar + 36));
        ctx.drawText(textRenderer, title, tx, y + (ROW_H - textRenderer.fontHeight) / 2 + 1, WHITE, false);

        // лого визуала после ника
        if (conv.clientLogo != null) {
            int b = 8;
            AvatarRenderer.drawSquare(ctx, conv.clientLogo, tx + textRenderer.getWidth(title) + 3, y + (ROW_H - b) / 2, b);
        }

        if (conv.type == Conversation.Type.CHANNEL && conv.onlineCount >= 0) {
            // зелёный счётчик онлайна канала
            String text = conv.onlineCount > 99 ? "99+" : String.valueOf(conv.onlineCount);
            int tw = textRenderer.getWidth(text);
            ctx.drawText(textRenderer, text, x + w - 4 - tw, y + (ROW_H - textRenderer.fontHeight) / 2 + 1, GREEN, false);
        } else if (conv.type == Conversation.Type.DM) {
            // статус онлайн/оффлайн
            int dot = 5;
            ctx.fill(x + w - 4 - dot, y + (ROW_H - dot) / 2, x + w - 4, y + (ROW_H - dot) / 2 + dot,
                    conv.online ? GREEN : GRAY_DOT);
        }

        rowHits.add(new RowHit(x, y, w, ROW_H, conv));
        return y + ROW_H + 4;
    }

    private int drawEmptyRow(DrawContext ctx, String text, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + ROW_H, ITEM_EMPTY);
        ctx.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2,
                y + (ROW_H - textRenderer.fontHeight) / 2 + 1, LABEL_DIM, false);
        return y + ROW_H + 4;
    }

    // чат

    private void renderChatPanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ctx.fill(x, y, x + w, y + h, PANEL);

        int pad = PAD;
        int innerX = x + pad;
        int innerW = w - pad * 2;

        // шапка диалога, там где ник собеседника и лого клиента
        int headH = 20;
        ctx.fill(innerX, y + pad, innerX + innerW, y + pad + headH, ITEM);
        Conversation conv = selected != null ? selected : net().getGeneral();
        int avatar = 14;
        AvatarRenderer.draw(ctx, conv.avatarUrl, conv.title, innerX + 3, y + pad + (headH - avatar) / 2, avatar);
        int htx = innerX + 3 + avatar + 4;
        ctx.drawText(textRenderer, conv.title, htx, y + pad + (headH - textRenderer.fontHeight) / 2 + 1, WHITE, false);
        int afterName = htx + textRenderer.getWidth(conv.title);
        if (conv.clientLogo != null) {
            int b = 9;
            AvatarRenderer.drawSquare(ctx, conv.clientLogo, afterName + 3, y + pad + (headH - b) / 2, b);
            afterName += 3 + b;
        }
        if (conv.type == Conversation.Type.DM) {
            int dot = 5;
            ctx.fill(afterName + 4, y + pad + (headH - dot) / 2, afterName + 4 + dot, y + pad + (headH - dot) / 2 + dot,
                    conv.online ? GREEN : GRAY_DOT);
        }

        // поле ввода (снизу) + кнопка отправки
        int inputH = 16;
        int inputY = y + h - pad - inputH;
        sendBtnW = 30;
        sendBtnH = inputH;
        sendBtnX = innerX + innerW - sendBtnW;
        sendBtnY = inputY;
        boolean sendHover = isHovered(mouseX, mouseY, sendBtnX, sendBtnY, sendBtnW, sendBtnH);
        ctx.fill(sendBtnX, sendBtnY, sendBtnX + sendBtnW, sendBtnY + sendBtnH, sendHover ? SEND_BG_HOVER : SEND_BG);
        String send = "Send";
        ctx.drawText(textRenderer, send, sendBtnX + (sendBtnW - textRenderer.getWidth(send)) / 2,
                sendBtnY + (sendBtnH - textRenderer.fontHeight) / 2 + 1, WHITE, false);

        input.setX(innerX);
        input.setY(inputY);
        input.setWidth(innerW - sendBtnW - 4);
        input.setHeight(inputH);
        input.render(ctx, mouseX, mouseY, 0f);

        // область сообщений между шапкой и вводом
        int areaY = y + pad + headH + 5;
        int areaH = inputY - 5 - areaY;
        ctx.enableScissor(innerX, areaY, innerX + innerW, areaY + areaH);
        renderMessages(ctx, conv, innerX, areaY, innerW, areaH);
        ctx.disableScissor();
    }

    private void renderMessages(DrawContext ctx, Conversation conv, int x, int areaY, int w, int areaH) {
        List<Message> msgs;
        synchronized (conv.messages) {
            msgs = new ArrayList<>(conv.messages);
        }

        int fh = textRenderer.fontHeight;
        int avatar = 16;
        int gap = 4;
        int pad = 4;
        int lineGap = 1;
        int maxBubbleW = (int) (w * 0.7f);
        int maxTextW = maxBubbleW - pad * 2;

        // перенос текста + высоты пузырей
        List<List<String>> wrapped = new ArrayList<>(msgs.size());
        int[] heights = new int[msgs.size()];
        int total = 0;
        for (int i = 0; i < msgs.size(); i++) {
            List<String> lines = wrapText(msgs.get(i).text, maxTextW);
            wrapped.add(lines);
            int textBlockH = lines.size() * fh + Math.max(0, lines.size() - 1) * lineGap;
            int bubbleH = pad + fh + 2 + textBlockH + 2 + fh + pad;
            heights[i] = Math.max(bubbleH, avatar);
            total += heights[i] + gap;
        }

        maxScroll = Math.max(0, total - areaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        // рисуем снизу вверх (последнее сообщение у нижней кромки)
        float drawY = areaY + areaH + scroll;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            int bh = heights[i];
            drawY -= bh + gap;
            if (drawY > areaY + areaH || drawY + bh < areaY) continue; // вне видимой зоны
            drawBubble(ctx, m, x, Math.round(drawY), w, avatar, wrapped.get(i), pad, lineGap);
        }
    }

    private void drawBubble(DrawContext ctx, Message m, int x, int y, int w, int avatar,
                            List<String> lines, int pad, int lineGap) {
        int fh = textRenderer.fontHeight;
        int longestLine = 0;
        for (String line : lines) {
            longestLine = Math.max(longestLine, textRenderer.getWidth(line));
        }
        int nameW = textRenderer.getWidth(m.username) + (m.clientLogo != null ? 11 : 0);
        int contentW = Math.max(longestLine, Math.max(nameW, textRenderer.getWidth(m.time)));
        int bubbleW = contentW + pad * 2;
        int textBlockH = lines.size() * fh + Math.max(0, lines.size() - 1) * lineGap;
        int bubbleH = pad + fh + 2 + textBlockH + 2 + fh + pad;

        if (m.mine) {
            int avX = x + w - avatar;
            int bx = avX - 4 - bubbleW;
            AvatarRenderer.draw(ctx, m.avatarUrl, m.username, avX, y + (Math.max(bubbleH, avatar) - avatar), avatar);
            drawBubbleBody(ctx, m, bx, y, bubbleW, bubbleH, BUBBLE_OUT, true, lines, pad, lineGap);
        } else {
            int avX = x;
            int bx = avX + avatar + 4;
            AvatarRenderer.draw(ctx, m.avatarUrl, m.username, avX, y + (Math.max(bubbleH, avatar) - avatar), avatar);
            drawBubbleBody(ctx, m, bx, y, bubbleW, bubbleH, BUBBLE_IN, false, lines, pad, lineGap);
        }
    }

    private void drawBubbleBody(DrawContext ctx, Message m, int x, int y, int bw, int bh, int bg, boolean mine,
                                List<String> lines, int pad, int lineGap) {
        int fh = textRenderer.fontHeight;
        ctx.fill(x, y, x + bw, y + bh, bg);
        int tx = x + pad;
        int ty = y + pad;
        ctx.drawText(textRenderer, m.username, tx, ty, WHITE, false);
        if (m.clientLogo != null) {
            int b = 8;
            AvatarRenderer.drawSquare(ctx, m.clientLogo, tx + textRenderer.getWidth(m.username) + 3, ty + (fh - b) / 2, b);
        }
        ty += fh + 2;
        for (String line : lines) {
            ctx.drawText(textRenderer, line, tx, ty, WHITE, false);
            ty += fh + lineGap;
        }
        ty += -lineGap + 2;
        int timeX = mine ? x + bw - pad - textRenderer.getWidth(m.time) : tx;
        ctx.drawText(textRenderer, m.time, timeX, ty, TIME_COLOR, false);
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (textRenderer.getWidth(word) > maxWidth) {
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    StringBuilder chunk = new StringBuilder();
                    for (int i = 0; i < word.length(); i++) {
                        char c = word.charAt(i);
                        if (chunk.length() > 0 && textRenderer.getWidth(chunk.toString() + c) > maxWidth) {
                            lines.add(chunk.toString());
                            chunk.setLength(0);
                        }
                        chunk.append(c);
                    }
                    if (chunk.length() > 0) {
                        line.append(chunk);
                    }
                    continue;
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (textRenderer.getWidth(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // состояния подключения

    private void renderConnectingPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        String text = "Подключение к meadow!network...";
        ctx.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2,
                y + h / 2 - textRenderer.fontHeight / 2, DIM, false);
        retryBtnW = 0;
    }

    private void renderErrorPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        int cx = x + w / 2;
        int ty = y + h / 2 - 24;

        String title = "Не удалось соединиться!";
        ctx.drawText(textRenderer, title, cx - textRenderer.getWidth(title) / 2, ty, ERROR, false);

        String sub = "Не удалось подключиться к meadow!network.";
        ty += textRenderer.fontHeight + 5;
        ctx.drawText(textRenderer, sub, cx - textRenderer.getWidth(sub) / 2, ty, DIM, false);

        String btn = "Повторить";
        retryBtnW = textRenderer.getWidth(btn) + 20;
        retryBtnH = 16;
        retryBtnX = cx - retryBtnW / 2;
        retryBtnY = ty + textRenderer.fontHeight + 8;
        ctx.fill(retryBtnX, retryBtnY, retryBtnX + retryBtnW, retryBtnY + retryBtnH, 0x40FF6767);
        ctx.drawText(textRenderer, btn, cx - textRenderer.getWidth(btn) / 2,
                retryBtnY + (retryBtnH - textRenderer.fontHeight) / 2 + 1, ERROR, false);
    }

    // ввод

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // кнопка повторить на экране, при неудачно подключении
        if (retryBtnW > 0 && isHovered(mouseX, mouseY, retryBtnX, retryBtnY, retryBtnW, retryBtnH)) {
            net().retry();
            return true;
        }

        // выбор диалога
        for (RowHit hit : rowHits) {
            if (isHovered(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                selectConversation(hit.conv());
                return true;
            }
        }

        // кнопка отправки
        if (sendBtnW > 0 && isHovered(mouseX, mouseY, sendBtnX, sendBtnY, sendBtnW, sendBtnH)) {
            sendCurrent();
            return true;
        }

        // поле ввода
        if (input != null && input.mouseClicked(mouseX, mouseY, button)) {
            input.setFocused(true);
            return true;
        }
        if (input != null) input.setFocused(false);

        // перетаскивание окна за верхнюю полосу
        if (isHovered(mouseX, mouseY, boxX, boxY, BOX_W, 18)) {
            dragging = true;
            dragOffsetX = (int) mouseX - boxX;
            dragOffsetY = (int) mouseY - boxY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void selectConversation(Conversation conv) {
        selected = conv;
        scroll = 0;
        net().setViewing(conv);
        net().ensureHistory(conv);
        if (input != null) {
            input.setFocused(true);
        }
    }

    private void sendCurrent() {
        if (input == null || selected == null) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        net().sendMessage(selected, text);
        input.setText("");
        scroll = 0;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            boxX = (int) mouseX - dragOffsetX;
            boxY = (int) mouseY - dragOffsetY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isHovered(mouseX, mouseY, boxX, boxY, BOX_W, BOX_H)) {
            scroll += (float) verticalAmount * 18f;
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (input != null && input.isFocused() && input.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendCurrent();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        if (input != null && input.isFocused() && input.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean isHovered(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String trim(String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) return text;
        return textRenderer.trimToWidth(text, maxWidth - textRenderer.getWidth("…")) + "…";
    }
}

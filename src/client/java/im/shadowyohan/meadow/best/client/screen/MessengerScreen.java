package im.shadowyohan.meadow.best.client.screen;

import im.shadowyohan.meadow.best.client.network.AvatarRenderer;
import im.shadowyohan.meadow.best.client.network.DroppedFiles;
import im.shadowyohan.meadow.best.client.network.ImageCache;
import im.shadowyohan.meadow.best.client.network.NetworkManager;
import im.shadowyohan.meadow.best.client.network.model.Conversation;
import im.shadowyohan.meadow.best.client.network.model.Message;
import im.shadowyohan.meadow.best.client.network.model.NetUser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
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

    // ── картинки в баблах ──
    private static final int IMG_MAX_W = 140;
    private static final int IMG_MAX_H = 100;

    private int boxX, boxY;
    private boolean dragging;
    private int dragOffsetX, dragOffsetY;

    private TextFieldWidget input;
    private Conversation selected;

    // прокрутка сообщений, 0 — последние снизу, рост — вверх к истории
    private float scroll;
    private float maxScroll;

    // картинка, приложенная к сообщению, но ещё не отправленная (стейджинг над полем ввода)
    private File pendingImageFile;

    // зоны кликов (заполняются при рендере)
    private final List<RowHit> rowHits = new ArrayList<>();
    private final List<MsgHit> msgHits = new ArrayList<>();
    private int sendBtnX, sendBtnY, sendBtnW, sendBtnH;
    private int attachBtnX, attachBtnY, attachBtnW, attachBtnH;
    private int retryBtnX, retryBtnY, retryBtnW, retryBtnH;
    private int friendsBtnX, friendsBtnY, friendsBtnW, friendsBtnH;
    private int stagingCloseX, stagingCloseY, stagingCloseW, stagingCloseH;
    private int netBtnX, netBtnY, netBtnW, netBtnH;
    private boolean netBtnEnabled;

    private record RowHit(int x, int y, int w, int h, Conversation conv) {
    }

    private record MsgHit(int x, int y, int w, int h, Message message) {
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
        msgHits.clear();

        // перетащенные на окно файлы - подхватываем как стейджинг картинки
        String dropped;
        while ((dropped = DroppedFiles.poll()) != null) {
            File f = new File(dropped);
            if (isImageFile(f)) pendingImageFile = f;
        }

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
        if (status == NetworkManager.Status.BANNED) {
            renderBannedPanel(ctx, panelX, panelY, panelW, panelH);
        } else if (status == NetworkManager.Status.FAILED) {
            renderErrorPanel(ctx, panelX, panelY, panelW, panelH);
        } else if (status == NetworkManager.Status.DISCONNECTED) {
            renderDisconnectedPanel(ctx, panelX, panelY, panelW, panelH);
        } else if (status != NetworkManager.Status.ONLINE) {
            renderConnectingPanel(ctx, panelX, panelY, panelW, panelH);
        } else {
            renderChatPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
        }

        renderNetworkButton(ctx, status, mouseX, mouseY);
    }

    private boolean isImageFile(File f) {
        if (!f.isFile()) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp");
    }

    // кнопка отключиться/подключиться - под окном мессенджера
    private void renderNetworkButton(DrawContext ctx, NetworkManager.Status status, int mouseX, int mouseY) {
        if (status == NetworkManager.Status.BANNED) {
            netBtnW = 0;
            return;
        }
        String label;
        netBtnEnabled = true;
        if (status == NetworkManager.Status.ONLINE) {
            label = "Отключиться";
        } else if (status == NetworkManager.Status.CONNECTING) {
            label = "Подключение...";
            netBtnEnabled = false;
        } else {
            label = "Подключиться";
        }
        netBtnW = textRenderer.getWidth(label) + 20;
        netBtnH = 16;
        netBtnX = boxX + (BOX_W - netBtnW) / 2;
        netBtnY = boxY + BOX_H + 5;
        boolean hover = netBtnEnabled && isHovered(mouseX, mouseY, netBtnX, netBtnY, netBtnW, netBtnH);
        ctx.fill(netBtnX, netBtnY, netBtnX + netBtnW, netBtnY + netBtnH, hover ? SEND_BG_HOVER : (netBtnEnabled ? SEND_BG : ITEM));
        int color = netBtnEnabled ? WHITE : DIM;
        ctx.drawText(textRenderer, label, netBtnX + (netBtnW - textRenderer.getWidth(label)) / 2,
                netBtnY + (netBtnH - textRenderer.fontHeight) / 2 + 1, color, false);
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
        curY += logoH + 4;

        // кнопка друзья/заявки
        int fbH = 14;
        friendsBtnX = curX;
        friendsBtnY = curY;
        friendsBtnW = innerW;
        friendsBtnH = fbH;
        boolean friendsHover = isHovered(mouseX, mouseY, friendsBtnX, friendsBtnY, friendsBtnW, friendsBtnH);
        ctx.fill(friendsBtnX, friendsBtnY, friendsBtnX + friendsBtnW, friendsBtnY + fbH, friendsHover ? ITEM_HOVER : ITEM);
        int pendingCount = net().getPendingRequests().size();
        String friendsLabel = pendingCount > 0 ? "друзья (" + pendingCount + ")" : "друзья";
        ctx.drawText(textRenderer, friendsLabel, friendsBtnX + (friendsBtnW - textRenderer.getWidth(friendsLabel)) / 2,
                friendsBtnY + (fbH - textRenderer.fontHeight) / 2 + 1, pendingCount > 0 ? GREEN : BRAND, false);
        curY += fbH + 6;

        // секция чаты
        ctx.drawText(textRenderer, "чаты", curX, curY, LABEL_DIM, false);
        curY += textRenderer.fontHeight + 3;
        curY = drawRow(ctx, net().getGeneral(), curX, curY, innerW, mouseX, mouseY);
        curY = drawRow(ctx, net().getClientChat(), curX, curY, innerW, mouseX, mouseY);

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

        // поле ввода (снизу) + кнопка отправки + кнопка приложить картинку
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

        attachBtnW = 16;
        attachBtnH = inputH;
        attachBtnX = innerX;
        attachBtnY = inputY;
        boolean attachHover = isHovered(mouseX, mouseY, attachBtnX, attachBtnY, attachBtnW, attachBtnH);
        ctx.fill(attachBtnX, attachBtnY, attachBtnX + attachBtnW, attachBtnY + attachBtnH,
                pendingImageFile != null ? SEND_BG : (attachHover ? ITEM_HOVER : ITEM));
        String clip = "+";
        ctx.drawText(textRenderer, clip, attachBtnX + (attachBtnW - textRenderer.getWidth(clip)) / 2,
                attachBtnY + (attachBtnH - textRenderer.fontHeight) / 2 + 1, WHITE, false);

        int inputX = innerX + attachBtnW + 3;
        int inputW = innerW - attachBtnW - 3 - sendBtnW - 4;

        // стейджинг приложенной картинки над полем ввода
        int stagingH = pendingImageFile != null ? 22 : 0;
        int stagingY = inputY - stagingH - (pendingImageFile != null ? 3 : 0);
        if (pendingImageFile != null) {
            ctx.fill(innerX, stagingY, innerX + innerW, stagingY + stagingH, ITEM);
            ImageCache.Handle preview = ImageCache.resolveLocal(pendingImageFile.getAbsolutePath());
            int thumb = 18;
            if (preview != null) {
                ctx.drawTexture(RenderLayer::getGuiTextured, preview.texture, innerX + 2, stagingY + 2,
                        0f, 0f, thumb, thumb, preview.width, preview.height, preview.width, preview.height);
            } else {
                ctx.fill(innerX + 2, stagingY + 2, innerX + 2 + thumb, stagingY + 2 + thumb, 0x20FFFFFF);
                ImageCache.drawSpinner(ctx, innerX + 2, stagingY + 2, thumb);
            }
            String name = trim(pendingImageFile.getName(), innerW - thumb - 30);
            ctx.drawText(textRenderer, name, innerX + thumb + 6, stagingY + (stagingH - textRenderer.fontHeight) / 2 + 1, WHITE, false);

            stagingCloseW = 14;
            stagingCloseH = 14;
            stagingCloseX = innerX + innerW - stagingCloseW - 2;
            stagingCloseY = stagingY + (stagingH - stagingCloseH) / 2;
            boolean closeHover = isHovered(mouseX, mouseY, stagingCloseX, stagingCloseY, stagingCloseW, stagingCloseH);
            ctx.fill(stagingCloseX, stagingCloseY, stagingCloseX + stagingCloseW, stagingCloseY + stagingCloseH,
                    closeHover ? 0x60FF6767 : 0x30FF6767);
            String closeLabel = "x";
            ctx.drawText(textRenderer, closeLabel, stagingCloseX + (stagingCloseW - textRenderer.getWidth(closeLabel)) / 2,
                    stagingCloseY + (stagingCloseH - textRenderer.fontHeight) / 2 + 1, ERROR, false);
        } else {
            stagingCloseW = 0;
        }

        input.setX(inputX);
        input.setY(inputY);
        input.setWidth(inputW);
        input.setHeight(inputH);
        input.render(ctx, mouseX, mouseY, 0f);

        // область сообщений между шапкой и вводом/стейджингом
        int areaY = y + pad + headH + 5;
        int bottomLimit = pendingImageFile != null ? stagingY - 3 : inputY - 5;
        int areaH = bottomLimit - areaY;
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
            Message m = msgs.get(i);
            List<String> lines = (m.text == null || m.text.isEmpty()) ? List.of() : wrapText(m.text, maxTextW);
            wrapped.add(lines);
            int textBlockH = lines.isEmpty() ? 0 : lines.size() * fh + Math.max(0, lines.size() - 1) * lineGap;
            boolean hasImage = m.imageUrl != null || m.localPreviewPath != null;
            int imgH = hasImage ? imageBlockSize(m)[1] : 0;
            int bodyH = (hasImage ? imgH + 3 : 0) + textBlockH;
            int bubbleH = pad + fh + 2 + bodyH + 2 + fh + pad;
            heights[i] = Math.max(bubbleH, avatar);
            total += heights[i] + gap;
        }

        maxScroll = Math.max(0, total - areaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        // долистали почти до самого верха истории - подгружаем следующую страницу (before_id)
        if (conv.hasMoreHistory && scroll >= maxScroll - 20) {
            net().loadMoreHistory(conv);
        }
        if (conv.loadingMore) {
            String loading = "Загрузка истории...";
            ctx.drawText(textRenderer, loading, x + (w - textRenderer.getWidth(loading)) / 2, areaY, LABEL_DIM, false);
        }

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

    // размер блока картинки в бабле с учётом пропорций (плейсхолдер пока не прогрузилась)
    private int[] imageBlockSize(Message m) {
        ImageCache.Handle h = m.imageUrl != null ? ImageCache.resolve(m.imageUrl)
                : m.localPreviewPath != null ? ImageCache.resolveLocal(m.localPreviewPath) : null;
        if (h == null) return new int[]{IMG_MAX_W, 70};
        float scale = Math.min(1f, Math.min((float) IMG_MAX_W / h.width, (float) IMG_MAX_H / h.height));
        return new int[]{Math.max(1, Math.round(h.width * scale)), Math.max(1, Math.round(h.height * scale))};
    }

    private void drawBubble(DrawContext ctx, Message m, int x, int y, int w, int avatar,
                            List<String> lines, int pad, int lineGap) {
        int fh = textRenderer.fontHeight;
        int longestLine = 0;
        for (String line : lines) {
            longestLine = Math.max(longestLine, textRenderer.getWidth(line));
        }
        boolean hasImage = m.imageUrl != null || m.localPreviewPath != null;
        int imgW = 0, imgH = 0;
        if (hasImage) {
            int[] sz = imageBlockSize(m);
            imgW = sz[0];
            imgH = sz[1];
        }
        int nameW = textRenderer.getWidth(m.username) + (m.clientLogo != null ? 11 : 0);
        int contentW = Math.max(longestLine, Math.max(nameW, textRenderer.getWidth(m.time)));
        contentW = Math.max(contentW, imgW);
        int bubbleW = contentW + pad * 2;
        int textBlockH = lines.isEmpty() ? 0 : lines.size() * fh + Math.max(0, lines.size() - 1) * lineGap;
        int bodyH = (hasImage ? imgH + 3 : 0) + textBlockH;
        int bubbleH = pad + fh + 2 + bodyH + 2 + fh + pad;

        int bx;
        if (m.mine) {
            int avX = x + w - avatar;
            bx = avX - 4 - bubbleW;
            AvatarRenderer.draw(ctx, m.avatarUrl, m.username, avX, y + (Math.max(bubbleH, avatar) - avatar), avatar);
            drawBubbleBody(ctx, m, bx, y, bubbleW, bubbleH, BUBBLE_OUT, true, lines, pad, lineGap, imgW, imgH);
        } else {
            int avX = x;
            bx = avX + avatar + 4;
            AvatarRenderer.draw(ctx, m.avatarUrl, m.username, avX, y + (Math.max(bubbleH, avatar) - avatar), avatar);
            drawBubbleBody(ctx, m, bx, y, bubbleW, bubbleH, BUBBLE_IN, false, lines, pad, lineGap, imgW, imgH);
        }
        msgHits.add(new MsgHit(bx, y, bubbleW, bubbleH, m));
    }

    private void drawBubbleBody(DrawContext ctx, Message m, int x, int y, int bw, int bh, int bg, boolean mine,
                                List<String> lines, int pad, int lineGap, int imgW, int imgH) {
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

        boolean hasImage = m.imageUrl != null || m.localPreviewPath != null;
        if (hasImage) {
            ImageCache.Handle handle = m.imageUrl != null ? ImageCache.resolve(m.imageUrl)
                    : ImageCache.resolveLocal(m.localPreviewPath);
            if (handle != null) {
                ctx.drawTexture(RenderLayer::getGuiTextured, handle.texture, tx, ty, 0f, 0f,
                        imgW, imgH, handle.width, handle.height, handle.width, handle.height);
            } else {
                ctx.fill(tx, ty, tx + imgW, ty + imgH, 0x20FFFFFF);
                ImageCache.drawSpinner(ctx, tx + imgW / 2 - 8, ty + imgH / 2 - 8, 16);
            }
            ty += imgH + 3;
        }

        for (String line : lines) {
            ctx.drawText(textRenderer, line, tx, ty, WHITE, false);
            ty += fh + lineGap;
        }
        if (!lines.isEmpty()) ty += -lineGap;
        ty += 2;

        int timeX = mine ? x + bw - pad - textRenderer.getWidth(m.time) : tx;
        ctx.drawText(textRenderer, m.time, timeX, ty, TIME_COLOR, false);

        if (mine && m.sendState != Message.SendState.SENT) {
            String marker = switch (m.sendState) {
                case UPLOADING -> "↑";
                case SENDING -> "...";
                case FAILED -> "!";
                default -> "";
            };
            if (!marker.isEmpty()) {
                int color = m.sendState == Message.SendState.FAILED ? ERROR : TIME_COLOR;
                int mx = timeX - textRenderer.getWidth(marker) - 3;
                ctx.drawText(textRenderer, marker, mx, ty, color, false);
            }
        }
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

    private void renderDisconnectedPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        String text = "Вы отключены от сети";
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

        drawRetryButton(ctx, cx, ty);
    }

    private void renderBannedPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        int cx = x + w / 2;
        int ty = y + h / 2 - 24;

        String title = "Вы забанены в meadow!network";
        ctx.drawText(textRenderer, title, cx - textRenderer.getWidth(title) / 2, ty, ERROR, false);

        String sub = "Доступ к сети ограничен.";
        ty += textRenderer.fontHeight + 5;
        ctx.drawText(textRenderer, sub, cx - textRenderer.getWidth(sub) / 2, ty, DIM, false);

        drawRetryButton(ctx, cx, ty);
    }

    private void drawRetryButton(DrawContext ctx, int cx, int ty) {
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (MsgHit hit : msgHits) {
                if (isHovered(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                    if (!hit.message().mine) {
                        MinecraftClient.getInstance().setScreen(
                                new ReportPopupScreen(this, hit.message(), selected));
                    }
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // кнопка отключиться/подключиться под окном
        if (netBtnW > 0 && netBtnEnabled && isHovered(mouseX, mouseY, netBtnX, netBtnY, netBtnW, netBtnH)) {
            if (net().getStatus() == NetworkManager.Status.ONLINE) {
                net().disconnect();
            } else {
                net().retry();
            }
            return true;
        }

        // кнопка друзья/заявки
        if (friendsBtnW > 0 && isHovered(mouseX, mouseY, friendsBtnX, friendsBtnY, friendsBtnW, friendsBtnH)) {
            MinecraftClient.getInstance().setScreen(new FriendsScreen(this));
            return true;
        }

        // кнопка повторить на экране ошибки/бана
        if (retryBtnW > 0 && isHovered(mouseX, mouseY, retryBtnX, retryBtnY, retryBtnW, retryBtnH)) {
            net().retry();
            return true;
        }

        // крестик сброса приложенной картинки
        if (stagingCloseW > 0 && isHovered(mouseX, mouseY, stagingCloseX, stagingCloseY, stagingCloseW, stagingCloseH)) {
            pendingImageFile = null;
            return true;
        }

        // кнопка приложить картинку
        if (attachBtnW > 0 && isHovered(mouseX, mouseY, attachBtnX, attachBtnY, attachBtnW, attachBtnH)) {
            openFileDialog();
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

    private void openFileDialog() {
        new Thread(() -> {
            FileDialog fd = new FileDialog((Frame) null, "Выберите картинку", FileDialog.LOAD);
            fd.setFilenameFilter((dir, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".gif") || n.endsWith(".webp");
            });
            fd.setVisible(true);
            String file = fd.getFile();
            String dir = fd.getDirectory();
            fd.dispose();
            if (file != null && dir != null) {
                File selected = new File(dir, file);
                MinecraftClient.getInstance().execute(() -> pendingImageFile = selected);
            }
        }, "meadow-filedialog").start();
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
        if (selected == null) return;
        String text = input != null ? input.getText().trim() : "";
        if (pendingImageFile != null) {
            File file = pendingImageFile;
            pendingImageFile = null;
            net().sendImageMessage(selected, text, file);
            if (input != null) input.setText("");
            scroll = 0;
            return;
        }
        if (text.isEmpty()) return;
        net().sendMessage(selected, text);
        if (input != null) input.setText("");
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

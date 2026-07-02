package im.shadowyohan.meadow.best.client.screen;

import im.shadowyohan.meadow.best.client.network.AvatarRenderer;
import im.shadowyohan.meadow.best.client.network.NetworkManager;
import im.shadowyohan.meadow.best.client.network.model.Friend;
import im.shadowyohan.meadow.best.client.network.model.NetUser;
import im.shadowyohan.meadow.best.client.network.model.PendingRequest;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// маленькое окошко поиск/друзья/заявки - открывается кнопкой в сайдбаре MessengerScreen
public class FriendsScreen extends Screen {

    private enum Tab {SEARCH, FRIENDS, REQUESTS}

    private static final int W = 220;
    private static final int H = 190;
    private static final int PAD = 6;
    private static final int ROW_H = 18;
    private static final int TAB_H = 16;

    private static final int BG = 0xD0000000;
    private static final int PANEL = 0x14FFFFFF;
    private static final int ITEM = 0x14FFFFFF;
    private static final int ITEM_HOVER = 0x24FFFFFF;
    private static final int TAB_SELECTED = 0x30FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int BRAND = 0xFFC4A7FF;
    private static final int GREEN = 0xFF79FF89;
    private static final int RED = 0xFFFF6767;
    private static final int BORDER = 0x26FFFFFF;

    private final Screen parent;
    private Tab tab = Tab.SEARCH;
    private TextFieldWidget searchField;

    private String lastFieldText = "";
    private String pendingQuery;
    private long pendingQueryAt;

    private float scroll;
    private float maxScroll;
    private final List<RowHit> hits = new ArrayList<>();

    private int boxX, boxY;

    private record RowHit(int x, int y, int w, int h, Runnable action) {
    }

    public FriendsScreen(Screen parent) {
        super(Text.literal("meadow!friends"));
        this.parent = parent;
    }

    private NetworkManager net() {
        return NetworkManager.get();
    }

    @Override
    protected void init() {
        boxX = (this.width - W) / 2;
        boxY = (this.height - H) / 2;

        searchField = new TextFieldWidget(this.textRenderer, boxX + PAD, boxY + PAD + TAB_H + 4, W - PAD * 2, 14, Text.literal("ник..."));
        searchField.setMaxLength(32);
        addSelectableChild(searchField);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        hits.clear();

        ctx.fill(boxX, boxY, boxX + W, boxY + H, BG);
        drawBorder(ctx, boxX, boxY, W, H, BORDER);

        int innerX = boxX + PAD;
        int innerW = W - PAD * 2;
        int tabY = boxY + PAD;
        renderTabs(ctx, innerX, tabY, innerW, mouseX, mouseY);

        int contentY = tabY + TAB_H + 4;
        if (tab == Tab.SEARCH) {
            contentY = renderSearchTab(ctx, innerX, contentY, innerW, mouseX, mouseY);
        }

        int listY = contentY;
        int listH = boxY + H - PAD - listY;
        ctx.enableScissor(innerX, listY, innerX + innerW, listY + listH);
        switch (tab) {
            case SEARCH -> renderSearchResults(ctx, innerX, listY, innerW, listH, mouseX, mouseY);
            case FRIENDS -> renderFriends(ctx, innerX, listY, innerW, listH, mouseX, mouseY);
            case REQUESTS -> renderRequests(ctx, innerX, listY, innerW, listH, mouseX, mouseY);
        }
        ctx.disableScissor();

        // дебаунс поиска - ждём паузу в наборе перед запросом к бэку
        if (tab == Tab.SEARCH) {
            String text = searchField.getText();
            if (!text.equals(lastFieldText)) {
                lastFieldText = text;
                pendingQuery = text;
                pendingQueryAt = System.currentTimeMillis();
            }
            if (pendingQuery != null && System.currentTimeMillis() - pendingQueryAt > 400) {
                net().searchUsers(pendingQuery);
                pendingQuery = null;
            }
        }
    }

    private void renderTabs(DrawContext ctx, int x, int y, int w, int mouseX, int mouseY) {
        int tabW = w / 3;
        String[] labels = {"Поиск", "Друзья", "Заявки"};
        Tab[] tabs = {Tab.SEARCH, Tab.FRIENDS, Tab.REQUESTS};
        for (int i = 0; i < 3; i++) {
            int tx = x + tabW * i;
            boolean sel = tab == tabs[i];
            boolean hover = isHovered(mouseX, mouseY, tx, y, tabW, TAB_H);
            ctx.fill(tx, y, tx + tabW - (i < 2 ? 1 : 0), y + TAB_H, sel ? TAB_SELECTED : hover ? ITEM_HOVER : ITEM);
            String label = labels[i];
            if (tabs[i] == Tab.REQUESTS && !net().getPendingRequests().isEmpty()) {
                label = label + " (" + net().getPendingRequests().size() + ")";
            }
            ctx.drawText(textRenderer, label, tx + (tabW - textRenderer.getWidth(label)) / 2,
                    y + (TAB_H - textRenderer.fontHeight) / 2 + 1, sel ? BRAND : DIM, false);
            int fi = i;
            hits.add(new RowHit(tx, y, tabW, TAB_H, () -> {
                tab = tabs[fi];
                scroll = 0;
            }));
        }
    }

    private int renderSearchTab(DrawContext ctx, int x, int y, int w, int mouseX, int mouseY) {
        searchField.setX(x);
        searchField.setY(y);
        searchField.setWidth(w);
        searchField.render(ctx, mouseX, mouseY, 0f);
        return y + 14 + 4;
    }

    private void renderSearchResults(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<NetUser> results = net().getSearchResults();
        if (results.isEmpty()) {
            drawHint(ctx, "Начните вводить ник", x, y, w);
            return;
        }
        int cy = y;
        for (NetUser u : results) {
            cy = drawUserRow(ctx, u.avatarUrl, u.clientLogo, u.username, x, cy, w, mouseX, mouseY,
                    "+", GREEN, () -> net().sendFriendRequest(u.id), u.requestSent);
        }
    }

    private void renderFriends(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<Friend> friends = net().getFriends();
        if (friends.isEmpty()) {
            drawHint(ctx, "Пока нет друзей", x, y, w);
            return;
        }
        int cy = y;
        for (Friend f : friends) {
            cy = drawUserRow(ctx, f.avatarUrl, f.clientLogo, f.username, x, cy, w, mouseX, mouseY,
                    "-", RED, () -> net().removeFriend(f.id));
        }
    }

    private void renderRequests(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<PendingRequest> pending = net().getPendingRequests();
        if (pending.isEmpty()) {
            drawHint(ctx, "Нет входящих заявок", x, y, w);
            return;
        }
        int cy = y;
        for (PendingRequest p : pending) {
            cy = drawUserRowTwoActions(ctx, p.avatarUrl, p.clientLogo, p.username, x, cy, w, mouseX, mouseY,
                    () -> net().acceptFriendRequest(p.userId), () -> net().declineFriendRequest(p.userId));
        }
    }

    private void drawHint(DrawContext ctx, String text, int x, int y, int w) {
        ctx.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2, y + 6, DIM, false);
    }

    private int drawUserRow(DrawContext ctx, String avatarUrl, String logoUrl, String username,
                             int x, int y, int w, int mouseX, int mouseY,
                             String actionLabel, int actionColor, Runnable action) {
        return drawUserRow(ctx, avatarUrl, logoUrl, username, x, y, w, mouseX, mouseY,
                actionLabel, actionColor, action, false);
    }

    private int drawUserRow(DrawContext ctx, String avatarUrl, String logoUrl, String username,
                             int x, int y, int w, int mouseX, int mouseY,
                             String actionLabel, int actionColor, Runnable action, boolean disabled) {
        boolean hover = isHovered(mouseX, mouseY, x, y, w, ROW_H);
        ctx.fill(x, y, x + w, y + ROW_H, hover ? ITEM_HOVER : ITEM);

        int avatar = 14;
        AvatarRenderer.draw(ctx, avatarUrl, username, x + 3, y + (ROW_H - avatar) / 2, avatar);
        int tx = x + 3 + avatar + 4;
        String title = trim(username, w - avatar - 40);
        ctx.drawText(textRenderer, title, tx, y + (ROW_H - textRenderer.fontHeight) / 2 + 1, WHITE, false);
        if (logoUrl != null) {
            int b = 8;
            AvatarRenderer.drawSquare(ctx, logoUrl, tx + textRenderer.getWidth(title) + 3, y + (ROW_H - b) / 2, b);
        }

        int btnW = 16;
        int btnX = x + w - btnW - 2;
        int color = disabled ? DIM : actionColor;
        boolean btnHover = !disabled && isHovered(mouseX, mouseY, btnX, y + 1, btnW, ROW_H - 2);
        ctx.fill(btnX, y + 1, btnX + btnW, y + ROW_H - 1, btnHover ? 0x60000000 | (color & 0xFFFFFF) : 0x30000000 | (color & 0xFFFFFF));
        ctx.drawText(textRenderer, actionLabel, btnX + (btnW - textRenderer.getWidth(actionLabel)) / 2,
                y + (ROW_H - textRenderer.fontHeight) / 2 + 1, color, false);
        if (disabled) {
            return y + ROW_H + 2;
        }
        hits.add(new RowHit(btnX, y + 1, btnW, ROW_H - 2, action));

        return y + ROW_H + 2;
    }

    private int drawUserRowTwoActions(DrawContext ctx, String avatarUrl, String logoUrl, String username,
                                       int x, int y, int w, int mouseX, int mouseY,
                                       Runnable accept, Runnable decline) {
        boolean hover = isHovered(mouseX, mouseY, x, y, w, ROW_H);
        ctx.fill(x, y, x + w, y + ROW_H, hover ? ITEM_HOVER : ITEM);

        int avatar = 14;
        AvatarRenderer.draw(ctx, avatarUrl, username, x + 3, y + (ROW_H - avatar) / 2, avatar);
        int tx = x + 3 + avatar + 4;
        String title = trim(username, w - avatar - 56);
        ctx.drawText(textRenderer, title, tx, y + (ROW_H - textRenderer.fontHeight) / 2 + 1, WHITE, false);
        if (logoUrl != null) {
            int b = 8;
            AvatarRenderer.drawSquare(ctx, logoUrl, tx + textRenderer.getWidth(title) + 3, y + (ROW_H - b) / 2, b);
        }

        int btnW = 16;
        int declineX = x + w - btnW - 2;
        int acceptX = declineX - btnW - 2;

        boolean acceptHover = isHovered(mouseX, mouseY, acceptX, y + 1, btnW, ROW_H - 2);
        ctx.fill(acceptX, y + 1, acceptX + btnW, y + ROW_H - 1, acceptHover ? 0x6079FF89 : 0x3079FF89);
        ctx.drawText(textRenderer, "✓", acceptX + (btnW - textRenderer.getWidth("✓")) / 2,
                y + (ROW_H - textRenderer.fontHeight) / 2 + 1, GREEN, false);
        hits.add(new RowHit(acceptX, y + 1, btnW, ROW_H - 2, accept));

        boolean declineHover = isHovered(mouseX, mouseY, declineX, y + 1, btnW, ROW_H - 2);
        ctx.fill(declineX, y + 1, declineX + btnW, y + ROW_H - 1, declineHover ? 0x60FF6767 : 0x30FF6767);
        ctx.drawText(textRenderer, "✗", declineX + (btnW - textRenderer.getWidth("✗")) / 2,
                y + (ROW_H - textRenderer.fontHeight) / 2 + 1, RED, false);
        hits.add(new RowHit(declineX, y + 1, btnW, ROW_H - 2, decline));

        return y + ROW_H + 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button);

        for (RowHit hit : hits) {
            if (isHovered(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        if (tab == Tab.SEARCH && searchField.mouseClicked(mouseX, mouseY, button)) {
            searchField.setFocused(true);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (tab == Tab.SEARCH && searchField.isFocused() && searchField.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        if (tab == Tab.SEARCH && searchField.isFocused() && searchField.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
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

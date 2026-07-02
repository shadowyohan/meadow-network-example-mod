# meadow-network - Example-Mod
> Version: Pre-release 1.1.0

Example-mod для разработчиков модов сети **meadow!network**. Содержит **всю логику чата**
(авторизация, общий чат, личные сообщения, друзья, presence) на голом Fabric 1.21.4
с ванильным рендером. Задача — взять отсюда логику, затестить и перенести её к себе
в клиент со своим кастомным рендером.

Рендер здесь намеренно простой: плоские прямоугольники, ванильный шрифт, квадратные
аватарки. Никаких скруглений, блюра и анимаций — дизайн вы сами делаете в своём клиенте.

<img src="https://i.ibb.co/350n94kG/Frame-30.png" alt="Frame-30" style="center">

---

## Что внутри

| | |
|---|---|
| MC | 1.21.4 (Yarn mappings) |
| Loader | Fabric Loader + Fabric API |
| Java | 21 |
| Сторона | только клиент (`environment: client`) |

Открыть мессенджер — клавиша **M** (меняется в настройках управления).

---

## Структура

```
client/network/
  MeadowEndpoint     конфиг сети (client_id/secret/url) + HMAC-подпись логина
  MeadowSocket       websocket с автореконнектом (чистый java.net.http)
  NetworkManager     ядро: авторизация, два сокета, загрузка данных, отправка, presence
  UserInfo           личность игрока (uid + username)
  AvatarRenderer     загрузка/кэш аватарок и лого, ванильный image-рендер
  network/model/     Conversation, Message, Friend, NetUser

client/screen/
  MessengerScreen    ванильный экран мессенджера (рисует состояние из NetworkManager)

client/
  MeadowNetworkClient  старт NetworkManager + кейбинд открытия
```

`NetworkManager` — синглтон (`NetworkManager.get()`), хранит всё состояние
потокобезопасно. Экран только рисует и шлёт действия, никакой логики в нём нет.

---

## Как прикрутить к себе

### 1. Реквизиты визуала

В [`MeadowEndpoint`](src/client/java/im/shadowyohan/meadow/best/client/network/MeadowEndpoint.java)
подставь свои значения (выдаются админом сети):

```java
public static final int    CLIENT_ID     = 3;
public static final String CLIENT_SECRET = "...";
public static final String BASE_URL      = "https://network.meadow.best";
```

Секрет по сети не уходит — им только подписывается логин (HMAC-SHA256), сервер
сверяет подпись своей копией.

### 2. Личность игрока

В [`UserInfo`](src/client/java/im/shadowyohan/meadow/best/client/network/UserInfo.java)
сейчас захардкожены тестовые `uid`/`username`. Подставь их из своей проты:

```java
public static volatile String uid = "1";
public static volatile String username = "shadow";
```

### 3. Перенос логики

Бери `network/` целиком — он не зависит от рендера, только от ванильного MC API
(`MinecraftClient`, `Text`, `SystemToast`, текстуры). `MessengerScreen` —
референс того, какие данные откуда читать; рисуй по нему свой UI.

---

## Протокол (вкратце)

**Авторизация** — `POST /auth/login` с телом `{client_id, uid, username, timestamp, signature}`,
в ответ JWT `session_token` (дальше как `Bearer` в заголовке).

**Два постоянных websocket'а** с автореконнектом:

- `…/ws/chat?token=` — общий чат (op: `message`, `user_joined`, `user_left`)
- `…/dm/ws/dm?token=` — личные (op: `dm`, `dm_sent`, `presence`, `presence_init`, `friend_request`, `friend_added`)

**REST** для начальной загрузки: `/users/me`, `/friends/`, `/dm/`, `/chat/messages`,
`/dm/{id}`, `/dm/read/{id}`.

Состояние self и диалогов фоном перечитывается раз в 40с — подхватывает смену
аватарок без релогина.

---

## Сборка

Стандартный Fabric-проект:

```bash
./gradlew build       # jar в build/libs/
./gradlew runClient   # запуск клиента для теста
```

---

> working on meadow!network

> Обновления поставляются в коммитах!

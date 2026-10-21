# `RTCPeerConnection` в EasyWebServer

Короткое описание: что нужно, чтобы из EasyWebServer поднять видео/аудио-связь между браузерами, и как этим пользоваться.

---

## 1. Что это

`RTCPeerConnection` — **JavaScript API браузера**, устанавливающий P2P-соединение между двумя браузерами для передачи видео, аудио и данных.

**Сервер медиа не передаёт.** EasyWebServer предоставляет три вспомогательных сервиса:

| Сервис | Класс | Протокол | Порт | Зачем |
|---|---|---|---|---|
| HTTP/HTTPS | `WebServer` | TCP | 9092 | Раздаёт HTML/JS страницы |
| Signaling | `WebRTCSignalingServer` | WebSocket | `VIDEOCHAT_PORT` | Браузеры обмениваются SDP/ICE |
| STUN | `StunServer` | UDP | `STUN_PORT` | Браузер узнаёт свой публичный IP:порт |

Медиа идёт **напрямую** между браузерами (P2P) — сервер его не видит.

---

## 2. Что нужно включить

**`Main.java`:**

```java
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_KEYSTORE", "/path/keystore.p12");
web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");
web.config("DEFAULT_PORT", "9092");

web.config("STUN_PORT", "19302");        // UDP
web.config("VIDEOCHAT_PORT", "8081");    // WebSocket signaling
```

**или `config.ini`:**

```json
{
    "HTTPS_ENABLED": true,
    "HTTPS_KEYSTORE": "/path/keystore.p12",
    "HTTPS_KEYSTORE_PASSWORD": "changeit",
    "DEFAULT_PORT": "9092",
    "STUN_PORT": 19302,
    "VIDEOCHAT_PORT": 8081
}
```

**Проброс на роутере (если за NAT):**

| Порт | Протокол | Куда |
|---|---|---|
| 9092 (или 443) | TCP | EasyWebServer |
| 8081 | TCP | WebRTCSignalingServer |
| 19302 | **UDP** | StunServer |

**Firewall (Linux):**

```bash
sudo ufw allow 9092/tcp
sudo ufw allow 8081/tcp
sudo ufw allow 19302/udp
```

---

## 3. Конфиг клиента

```javascript
const pc = new RTCPeerConnection({
    iceServers: [
        { urls: 'stun:www.smwrap.ru:19302' }
    ]
});
```

**Если STUN не пробивает** (симметричный NAT с обеих сторон) — нужен **TURN**:

```javascript
const pc = new RTCPeerConnection({
    iceServers: [
        { urls: 'stun:www.smwrap.ru:19302' },
        {
            urls: 'turn:turn.example.com:3478',
            username: 'user',
            credential: 'pass'
        }
    ]
});
```

**Важно:** `getUserMedia` и `getDisplayMedia` работают **только по HTTPS** (или на `localhost`). `www.smwrap.ru` — HTTPS, всё ок.

---

## 4. Минимальный 1:1 видеозвонок

### 4.1. Подключение к signaling

```javascript
const ws = new WebSocket(
    `wss://${location.hostname}:8081/ws/publish?room=test&pass=`
);
```

Публикующий подключается к `/ws/publish`, зритель — к `/ws/subscribe`. Подробнее — `AUTH_AND_MESSAGING.md`, §6.

### 4.2. Публикующий (вещатель)

```javascript
let pc, localStream;

async function startBroadcast() {
    // 1. Захват экрана или камеры
    localStream = await navigator.mediaDevices.getDisplayMedia({ video: true });

    // 2. WebSocket для signaling
    ws = new WebSocket(`wss://${location.hostname}:8081/ws/publish?room=test`);

    ws.onopen = () => console.log('signaling connected');

    ws.onmessage = async (e) => {
        const msg = JSON.parse(e.data);

        if (msg.viewerJoined) {
            // 3. Новый зритель — создаём для него RTCPeerConnection
            pc = new RTCPeerConnection({
                iceServers: [{ urls: 'stun:www.smwrap.ru:19302' }]
            });

            // 4. Добавляем свои треки
            localStream.getTracks().forEach(t => pc.addTrack(t, localStream));

            // 5. ICE-кандидаты → серверу для этого зрителя
            pc.onicecandidate = (ev) => {
                if (ev.candidate) {
                    ws.send(JSON.stringify({
                        target: msg.viewerJoined,
                        candidate: ev.candidate
                    }));
                }
            };

            // 6. Создаём offer
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            ws.send(JSON.stringify({
                target: msg.viewerJoined,
                sdp: pc.localDescription
            }));
        }

        // 7. Пришёл answer от зрителя
        if (msg.sdp && pc) {
            await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
        }

        // 8. Пришёл ICE от зрителя
        if (msg.candidate && pc) {
            await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
        }
    };
}
```

### 4.3. Зритель

```javascript
function startViewer() {
    let pc;

    ws = new WebSocket(`wss://${location.hostname}:8081/ws/subscribe?room=test`);

    ws.onmessage = async (e) => {
        const msg = JSON.parse(e.data);

        // 1. Регистрация
        if (msg.viewerId) {
            console.log('joined as', msg.viewerId);
            return;
        }

        // 2. Offer от вещателя
        if (msg.sdp) {
            pc = new RTCPeerConnection({
                iceServers: [{ urls: 'stun:www.smwrap.ru:19302' }]
            });

            // 3. Приём медиа
            pc.ontrack = (ev) => {
                document.getElementById('remoteVideo').srcObject = ev.streams[0];
            };

            // 4. Свои ICE → вещателю
            pc.onicecandidate = (ev) => {
                if (ev.candidate) {
                    ws.send(JSON.stringify({
                        target: 'publisher',
                        candidate: ev.candidate
                    }));
                }
            };

            // 5. Answer
            await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            ws.send(JSON.stringify({
                target: 'publisher',
                sdp: pc.localDescription
            }));
        }

        // 6. ICE от вещателя
        if (msg.candidate && pc) {
            await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
        }
    };
}
```

---

## 5. Порядок установки соединения

```
Вещатель                          Signaling                          Зритель
   │                                 │                                  │
   │ ── startBroadcast() ──►         │                                  │
   │ ◄── viewerJoined ───────────────│◄──── startViewer() ──────────────│
   │                                 │──── viewerId ───────────────────►│
   │ ── offer ──────────────────────►│──── offer ──────────────────────►│
   │                                 │◄─── answer ──────────────────────│
   │◄─ answer ───────────────────────│                                  │
   │ ── ICE ────────────────────────►│──── ICE ────────────────────────►│
   │◄─ ICE ──────────────────────────│◄─── ICE ─────────────────────────│
   │                                 │                                  │
   │◄══════════════ SRTP медиа (P2P, напрямую) ═════════════════════════►│
```

**Одна сессия = один `RTCPeerConnection`.** На каждого зрителя — свой `pc`. Вещатель держит `Map<viewerId, RTCPeerConnection>`.

---

## 6. Проверка

### 6.1. STUN работает?

```bash
sudo apt install stun-client
stun www.smwrap.ru 19302
```

Ожидаемо:

```
Binding test: success
Local addr: 192.168.1.42:54321
Mapped addr: 203.0.113.10:54321
```

`Mapped addr` — это ваш публичный IP:порт. Значит STUN работает.

### 6.2. Signaling работает?

```bash
# Смотрим, слушает ли сервер
sudo ss -ltnp | grep ':8081'

# Проверяем WebSocket handshake
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     https://www.smwrap.ru:8081/ws/publish?room=test
```

Ожидаемо: `HTTP/1.1 101 Switching Protocols` и `Sec-WebSocket-Accept: ...`.

### 6.3. Медиа идёт?

Открыть `chrome://webrtc-internals`:

| Что смотреть | Ожидаемо |
|---|---|
| `ICE connection state` | `connected` |
| `Signaling state` | `stable` |
| `ICE candidates` | есть `host` + `srflx` |
| `Stats → inbound-rtp` | `bytesReceived` растёт |

**Если `ICE connection state = failed`** — STUN не пробивает, нужен TURN.

---

## 7. Частые проблемы

| Симптом | Причина | Решение |
|---|---|---|
| `getDisplayMedia is not a function` | Открыто по `http://` | Использовать HTTPS |
| `ICE failed` | STUN не работает / симметричный NAT | Проверить `stun www.smwrap.ru 19302`; при симметричном NAT добавить TURN |
| `signaling closed` сразу | Порт 8081 закрыт файрволом | `sudo ufw allow 8081/tcp` |
| Медиа не идёт, но `connected` | `ontrack` не сработал | Проверить, что вещатель сделал `addTrack` |
| Только звук | Нет видео-трека | `getDisplayMedia({video: true, audio: false})` |
| `SecurityError` на getUserMedia | `http://` не `localhost` | HTTPS |
| STUN отвечает, но ICE `failed` | Обе стороны за симметричным NAT | TURN обязателен |

---

## 8. Итог

- **`RTCPeerConnection` — в браузере**, не на сервере.
- Сервер даёт **три сервиса**: HTTPS (страницы), WebSocket (signaling), STUN (публичный IP).
- Включение: `HTTPS_ENABLED=true`, `STUN_PORT=19302`, `VIDEOCHAT_PORT=8081`.
- Клиент: `new RTCPeerConnection({ iceServers: [{ urls: 'stun:www.smwrap.ru:19302' }] })`.
- Медиа идёт **P2P**, сервер его не передаёт.
- Если STUN не пробивает — **TURN** (`coturn`).
- Отладка — `chrome://webrtc-internals`.
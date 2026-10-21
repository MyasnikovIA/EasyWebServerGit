# Самоподписанный P12-сертификат + регистрация домена в Reg.ru

Полная инструкция по созданию самоподписанного PKCS12 (`keystore.p12`) сертификата на Linux Mint и настройке DNS-записей в Reg.ru, чтобы домен указывал на ваш сервер. Подходит для локальной разработки, внутренней сети, тестовых стендов — когда нужно быстро и без Let's Encrypt.

> **Важно, прежде чем начать:** самоподписанный сертификат **не требует** TXT-записей `_acme-challenge` и валидации через ACME. В Reg.ru настраиваются только **A-записи** (и/или `AAAA`), чтобы домен резолвился в IP вашего сервера. Само доверие к сертификату достигается добавлением его в truststore клиента (§7), а не DNS-записями.

---

# 1. Требования

| Компонент | Минимум | Назначение |
|---|---|---|
| Linux Mint | 21+ | сервер |
| JDK | 21+ (JBR 25 подходит) | `keytool` — генерация keystore |
| `openssl` | 1.1+ | создание PEM/конвертация |
| `libnss3-tools` | — | `certutil` для Chrome/Firefox |
| Белый/серый IP или домен | — | домен в Reg.ru |
| Порты TCP | 9092 (или иной) | сервер слушает |

`setcap` на `java` **не нужен** — сервер слушает непривилегированный порт (9092).

---

# 2. Установка программ в Linux Mint

## 2.1. JDK (для `keytool`)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk
```

Проверка:

```bash
java -version
# openjdk version "21.x.x"

which keytool
# /usr/bin/keytool

keytool -help | head -3
```

Если используете JBR из JetBrains (`/home/myasnikovia/.jdks/jbr-25.0.4.1/bin/`) — `keytool` уже там:

```bash
/home/myasnikovia/.jdks/jbr-25.0.4.1/bin/keytool -help
```

## 2.2. OpenSSL

На Linux Mint `openssl` обычно установлен. Если нет:

```bash
sudo apt install -y openssl
```

Проверка:

```bash
openssl version
# OpenSSL 3.x.x
```

## 2.3. `certutil` (для Chrome/Firefox на Linux)

Нужен для добавления сертификата в NSS-хранилище браузеров.

```bash
sudo apt install -y libnss3-tools
```

Проверка:

```bash
certutil -H | head -3
```

## 2.4. Дополнительно (для диагностики TLS)

```bash
sudo apt install -y ca-certificates curl net-tools dnsutils
```

`dnsutils` — для `dig`/`nslookup`, чтобы проверить DNS-записи Reg.ru. `curl` — для проверки TLS. `net-tools` — `netstat`.

## 2.5. Проверка, что порт свободен

```bash
sudo ss -ltnp | grep ':9092 '
```

Пусто — порт свободен. Если занят (например, другой Java-процесс) — найдите и остановите:

```bash
sudo ss -ltnp | grep ':9092 '
# LISTEN 0  50  0.0.0.0:9092  0.0.0.0:*  users:(("java",pid=12345,fd=...))
sudo kill 12345
```

---

# 3. Создание самоподписанного P12-сертификата

## 3.1. Что входит в сертификат — обязательно SAN

Современные браузеры (Chrome 58+, Firefox 48+, Edge, Safari) сверяют hostname с полем **`Subject Alternative Name` (SAN)**, **не** с `CN` (Common Name). Если SAN не содержит нужного домена/IP — браузер откажет с `NET::ERR_CERT_COMMON_NAME_INVALID`, даже если CN совпадает.

Поэтому SAN нужно составить **до** генерации. Типичный набор для локального/внутреннего использования:

- `DNS:myapp.local` — основной домен (тот, по которому заходят).
- `DNS:www.myapp.local` — с `www`.
- `DNS:localhost` — для локальных тестов.
- `IP:127.0.0.1` — для `https://127.0.0.1:9092/`.
- `IP:192.168.1.42` — внутренний IP сервера в LAN.

Для **публичного домена** (например, `smwrap.ru`):

- `DNS:smwrap.ru`
- `DNS:www.smwrap.ru`
- `IP:` — можно не добавлять, если не планируете обращаться по IP.

**Правила SAN:**

- `dns:` — доменное имя, wildcard `*.example.com` работает только на одном уровне (`a.example.com` — да, `a.b.example.com` — нет).
- `ip:` — IPv4/IPv6.
- Перечисление через запятую, без пробелов.

## 3.2. Вариант A — `keytool` (прямо в PKCS12, самый простой)

Одна команда создаёт и ключ, и сертификат, и keystore.

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity 3650 \
  -storetype PKCS12 \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass 'changeit' \
  -keypass 'changeit' \
  -dname "CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU" \
  -ext "SAN=dns:myapp.local,dns:www.myapp.local,dns:localhost,ip:127.0.0.1,ip:192.168.1.42"
```

**Разбор параметров:**

| Параметр | Значение |
|---|---|
| `-alias server` | алиас записи в keystore. `server` — стандарт, `SSLManager` перебирает все alias'ы, так что имя важно только для удобства. |
| `-keyalg RSA -keysize 2048` | алгоритм и длина ключа. 2048 — минимум для TLS 1.2/1.3. 4096 — тяжелее, но и надёжнее. |
| `-sigalg SHA256withRSA` | алгоритм подписи. SHA-256 обязателен для всех современных браузеров (SHA-1 не принимается с 2017 г.). |
| `-validity 3650` | срок действия — **10 лет**. Для локального удобно. **Не ставьте 36500** — Chrome не принимает > 398 дней для публичных CA, но для self-signed работает; тем не менее разумный максимум — 10 лет. |
| `-storetype PKCS12` | формат `.p12`. JKS устарел, но тоже читается EasyWebServer. |
| `-storepass changeit` | пароль keystore. **На проде сменить.** |
| `-keypass changeit` | пароль приватного ключа. Обычно совпадает. |
| `-dname "..."` | subject: `CN`, `OU`, `O`, `L`, `S`, `C`. `CN` — для совместимости со старыми клиентами, но **не** главное. |
| `-ext "SAN=..."` | **обязательное** — список `dns:` и `ip:` через запятую. |

**Если нужно добавить домен позже** — не пересоздавайте, добавьте SAN через `keytool -genkeypair` снова с новым `-ext` (это фактически перегенерация) — проще сразу указать все нужные SAN.

## 3.3. Вариант B — `openssl` (PEM → P12)

Полезно, если удобнее работать с PEM (например, вы будете разворачивать тот же сертификат на Nginx/Apache, где нужны `.pem`-файлы).

### 3.3.1. Создать приватный ключ + сертификат

```bash
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout /opt/easywebserver/certs/privkey.pem \
  -out /opt/easywebserver/certs/fullchain.pem \
  -days 3650 \
  -sha256 \
  -subj "/CN=myapp.local/OU=Dev/O=MyCompany/L=City/S=State/C=RU" \
  -addext "subjectAltName=DNS:myapp.local,DNS:www.myapp.local,DNS:localhost,IP:127.0.0.1,IP:192.168.1.42"
```

**Разбор:**

| Параметр | Значение |
|---|---|
| `-x509` | создать самоподписанный (а не запрос в CA). |
| `-newkey rsa:2048` | сгенерировать новый ключ RSA 2048. |
| `-nodes` | ключ **без пароля** (иначе `openssl` будет запрашивать пароль при каждом чтении). |
| `-keyout privkey.pem` | приватный ключ (формат PKCS#8). |
| `-out fullchain.pem` | публичный сертификат. |
| `-days 3650` | 10 лет. |
| `-sha256` | алгоритм хэша (обязательно SHA-256). |
| `-subj "..."` | subject (CN и т.д.). |
| `-addext "subjectAltName=..."` | SAN — обязательное поле. |

### 3.3.2. Проверить PEM-сертификат

```bash
openssl x509 -in /opt/easywebserver/certs/fullchain.pem -noout -subject -issuer -dates -ext subjectAltName
```

Ожидаемый вывод:

```
subject=CN = myapp.local, OU = Dev, O = MyCompany, L = City, S = State, C = RU
issuer=CN = myapp.local, OU = Dev, O = MyCompany, L = City, S = State, C = RU
notBefore=... notAfter=...
X509v3 Subject Alternative Name:
    DNS:myapp.local, DNS:www.myapp.local, DNS:localhost, IP Address:127.0.0.1, IP Address:192.168.1.42
```

`issuer = subject` — это нормально для self-signed. Браузер не доверяет, пока сертификат не добавлен в truststore (§7).

### 3.3.3. Проверить формат приватного ключа

```bash
head -1 /opt/easywebserver/certs/privkey.pem
```

- `-----BEGIN PRIVATE KEY-----` — **PKCS#8**, годится.
- `-----BEGIN RSA PRIVATE KEY-----` — **PKCS#1**, EasyWebServer его **не читает**. Конвертируйте:

  ```bash
  openssl pkcs8 -topk8 -nocrypt \
    -in /opt/easywebserver/certs/privkey.pem \
    -out /opt/easywebserver/certs/privkey_pkcs8.pem
  ```

### 3.3.4. Упаковать PEM в `keystore.p12`

```bash
openssl pkcs12 -export \
  -in /opt/easywebserver/certs/fullchain.pem \
  -inkey /opt/easywebserver/certs/privkey_pem_or_pkcs8.pem \
  -out /opt/easywebserver/certs/keystore.p12 \
  -name server \
  -passout pass:changeit
```

Если хотите **использовать PEM напрямую** (без упаковки в `.p12`) — в `EasyWebServer` задайте `HTTPS_CERT` + `HTTPS_KEY` (§5.2). Приоритет у `HTTPS_KEYSTORE`, так что если хотите PEM — просто не задавайте keystore.

## 3.4. Проверить созданный `keystore.p12`

```bash
keytool -list -v \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass 'changeit'
```

Ключевые строки:

```
Alias name: server
Entry type: PrivateKeyEntry
Owner: CN=myapp.local, OU=Dev, ...
Issuer: CN=myapp.local, OU=Dev, ...                    ← = Owner ⇒ self-signed
Valid from: ... until: ...
Subject Alternative Name:
    DNS:myapp.local, DNS:www.myapp.local, DNS:localhost, IP Address:127.0.0.1, IP Address:192.168.1.42
```

`Issuer = Owner` — ожидаемо. `SAN` — содержит все нужные имена.

## 3.5. Права на файл

```bash
sudo mkdir -p /opt/easywebserver/certs
sudo chown -R myasnikovia:myasnikovia /opt/easywebserver/certs
chmod 700 /opt/easywebserver/certs
chmod 600 /opt/easywebserver/certs/keystore.p12

# Если используете PEM:
chmod 600 /opt/easywebserver/certs/privkey*.pem
chmod 644 /opt/easywebserver/certs/fullchain.pem
```

`600` на приватный ключ — обязательное требование безопасности.

## 3.6. Экспорт публичного сертификата для клиентов

Нужен файл `.crt` (без приватного ключа), чтобы добавлять в truststore клиентов.

**Из keystore:**

```bash
keytool -exportcert \
  -alias server \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass 'changeit' \
  -rfc \
  -file /opt/easywebserver/certs/myapp.crt
```

**Из PEM:**

```bash
cp /opt/easywebserver/certs/fullchain.pem /opt/easywebserver/certs/myapp.crt
```

`-rfc` → PEM (`-----BEGIN CERTIFICATE-----`). Файл `myapp.crt` раздаётся клиентам, приватный ключ никогда.

---

# 4. Регистрация домена в Reg.ru

**Что регистрируется в Reg.ru для self-signed:**

- **A-запись** — домен → IPv4 сервера. **Обязательно.**
- **AAAA-запись** — домен → IPv6 сервера. Если IPv6 есть.
- **CNAME** для `www` → `@` (опционально; можно добавить `www` как отдельную `A`).
- **TXT-записи `_acme-challenge`** — **НЕ НУЖНЫ**. Они только для Let's Encrypt. Для self-signed не нужны.
- **NS-записи** — используются Reg.ru по умолчанию (`ns1.reg.ru`, `ns2.reg.ru`). Менять не надо, если только вы не используете свой DNS.

## 4.1. Вход в панель Reg.ru

1. Открыть `https://www.reg.ru/user/account/` → войти в аккаунт.
2. В левом меню — **«Мои домены»** (или `https://www.reg.ru/user/domain_list`).
3. Найти домен → нажать **«Управление»**.
4. В левом меню — **«DNS-серверы и управление зоной»**.

## 4.2. Проверить NS-серверы (должны быть Reg.ru)

Раздел **«DNS-серверы»** — по умолчанию:

```
ns1.reg.ru
ns2.reg.ru
```

Если стоят эти — идите в §4.3. Если другие — либо оставьте их (тогда DNS-записи настраиваются у того провайдера), либо переключите на `ns1.reg.ru`/`ns2.reg.ru` и подождите 1–4 часа на обновление делегирования.

## 4.3. Настроить A-запись

В разделе **«Управление зоной»** (или «DNS-серверы и управление зоной») найдите **«Добавить запись»** или **«Создать новую запись»**.

**Запись 1: A для корневого домена**

| Поле | Значение |
|---|---|
| **Тип записи** | A |
| **Subdomain (Имя)** | `@` (или оставьте пустым — означает корень домена) |
| **IP-адрес** | `203.0.113.10` (внешний IP вашего сервера) |

**Запись 2: A для `www`**

| Поле | Значение |
|---|---|
| **Тип записи** | A |
| **Subdomain (Имя)** | `www` |
| **IP-адрес** | `203.0.113.10` (тот же IP) |

**Альтернатива для `www`** — CNAME:

| Поле | Значение |
|---|---|
| **Тип записи** | CNAME |
| **Subdomain (Имя)** | `www` |
| **Canonical name (Значение)** | `smwrap.ru.` (с точкой в конце) |

CNAME удобнее: изменил IP в A-записи для `@` — `www` автоматически подхватил.

**Запись 3 (опционально): AAAA для IPv6**

Если у вашего сервера есть IPv6 (проверьте `ip -6 addr show` на сервере):

| Тип | Имя | Значение |
|---|---|---|
| AAAA | `@` | `2001:db8::1` |
| AAAA | `www` | `2001:db8::1` |

Если IPv6 нет — не добавляйте.

> **Важно: какой IP писать?**
> - Если сервер с **белым IP** — внешний IP сервера.
> - Если сервер за **роутером** (NAT) — **внешний (белый) IP роутера**, а на роутере настроен **port forward** `TCP 443 → <server>:9092` и `TCP 9092 → <server>:9092`.
> - Узнать внешний IP: `curl ifconfig.me` с сервера.

## 4.4. TTL

**TTL** — время кэширования записи. Значения:

- `300` (5 минут) — для разработки, когда IP может меняться.
- `3600` (1 час) — обычно.
- `86400` (1 день) — редко меняется.

Для тестового стенда ставьте **300** — быстрее подхватываются изменения.

## 4.5. Сохранить и подождать

Нажмите **«Сохранить»** / **«Готово»**. Зона обновится в течение **5–30 минут** (TTL). Полное делегирование может занять до **24–48 часов** для новых доменов.

## 4.6. Проверить DNS

С сервера и с клиента:

```bash
# Проверить A-запись
dig +short A smwrap.ru
dig +short A www.smwrap.ru

# Ожидается: 203.0.113.10

# Проверить через публичный DNS
dig +short A smwrap.ru @8.8.8.8
dig +short A smwrap.ru @1.1.1.1

# Проверить NS
dig +short NS smwrap.ru
# ns1.reg.ru.
# ns2.reg.ru.
```

Если `dig` возвращает пусто — либо записи ещё не применились (подождите), либо они не сохранены (проверьте панель), либо NS-серверы не Reg.ru.

## 4.7. Что делать, если домен на другом регистраторе

Если домен у другого регистратора (не Reg.ru), но хотите пользоваться DNS Reg.ru:

1. В панели текущего регистратора замените NS-серверы на `ns1.reg.ru` и `ns2.reg.ru`.
2. В Reg.ru **добавьте домен на обслуживание** — «Услуги → DNS-хостинг», добавьте домен, настройте A-записи.

Либо настраивайте DNS у текущего регистратора по той же схеме (A-записи для `@` и `www`, TTL 300).

---

# 5. Настройка EasyWebServer на сертификат

## 5.1. Через keystore

`Main.java`:

```java
web.config("DEFAULT_HOST", "0.0.0.0");
web.config("DEFAULT_PORT", "9092");
web.config("HTTPS_ENABLED",           "true");
web.config("HTTPS_KEYSTORE",          "/opt/easywebserver/certs/keystore.p12");
web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");
```

`config.ini`:

```json
"https": {
    "enabled": true,
    "keystore": "/opt/easywebserver/certs/keystore.p12",
    "password": "changeit"
},
"DEFAULT_PORT": "9092"
```

## 5.2. Через PEM

```java
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_CERT",    "/opt/easywebserver/certs/fullchain.pem");
web.config("HTTPS_KEY",     "/opt/easywebserver/certs/privkey_pkcs8.pem");
```

Если ключ в PKCS#1 — конвертируйте (§3.3.3).

## 5.3. Запуск и проверка лога

```bash
java -jar EasyWebServer-1.0-SNAPSHOT.jar
```

В логе при старте:

```
[WebServer] Starting in HTTPS mode...
[WebServer] Keystore: /opt/easywebserver/certs/keystore.p12
[WebServer] Listen port: 9092
[SSLManager] Loading keystore: /opt/easywebserver/certs/keystore.p12
[SSLManager] Alias 'server'
[SSLManager]   Subject: CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU
[SSLManager]   Issuer : CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU
[SSLManager]   Valid  : ... .. ...
[SSLManager] SSLContext init OK (keystore), protocol=TLS
[SSLManager] SSLServerSocket ready on port 9092
[SSLManager] Enabled protocols: TLSv1.2,TLSv1.3
```

---

# 6. Проверка TLS с сервера и из LAN

## 6.1. Локально

```bash
# Получить сертификат, который отдаёт сервер
echo | openssl s_client -connect 127.0.0.1:9092 -servername myapp.local 2>/dev/null | \
  openssl x509 -noout -subject -issuer -dates -ext subjectAltName
```

Должны совпасть subject, issuer (= subject), dates, SAN с тем, что создавали.

## 6.2. `curl` (самоподписанный сертификат требует `--cacert` или `-k`)

```bash
# Без проверки сертификата (только проверить TLS-соединение):
curl -vk https://127.0.0.1:9092/

# С явно указанным сертификатом:
curl -v --cacert /opt/easywebserver/certs/myapp.crt https://myapp.local:9092/

# Ожидается в выводе:
# * SSL certificate verify ok.
# < HTTP/1.1 200 OK
```

Если `SSL certificate verify ok` **не появилось** — сертификат не проходит проверку: либо SAN не покрывает `myapp.local`, либо `--cacert` указывает не на тот файл.

## 6.3. Из локальной сети (другая машина)

```bash
# По IP
curl -vk https://192.168.1.42:9092/

# По домену — только если DNS-запись уже применилась и myapp.local резолвится в 192.168.1.42
curl -vk https://myapp.local:9092/
```

## 6.4. Из интернета (если настроен проброс на роутере)

```bash
curl -vk https://smwrap.ru/
curl -vk https://smwrap.ru:9092/
```

**Проброс портов на роутере** (см. §10) — без него внешние запросы до сервера не дойдут.

---

# 7. Регистрация сертификата на клиентах (чтобы не было предупреждений)

Самоподписанный сертификат работает в браузере **только если он добавлен в truststore** клиента. Ниже — по платформам.

## 7.1. Linux Mint (системное хранилище CA)

```bash
# 1. Скопировать .crt в системный каталог
sudo cp /opt/easywebserver/certs/myapp.crt /usr/local/share/ca-certificates/myapp.crt

# 2. Обновить хранилище
sudo update-ca-certificates
# Ожидаемый вывод: Adding debian:myapp.pem

# 3. Проверить (curl теперь без --cacert)
curl -v https://myapp.local:9092/
```

**Отзыв:**

```bash
sudo rm /usr/local/share/ca-certificates/myapp.crt
sudo update-ca-certificates --fresh
```

## 7.2. Chrome / Chromium / Edge на Linux (NSS)

Chrome на Linux **не** использует системное хранилище CA (в отличие от Windows). Он смотрит в **NSS-базу пользователя** `~/.pki/nssdb`.

```bash
# 1. Если база ещё не создана
mkdir -p $HOME/.pki/nssdb
certutil -d sql:$HOME/.pki/nssdb -N --empty-password

# 2. Добавить сертификат как доверенный CA
certutil -d sql:$HOME/.pki/nssdb -A \
  -t "C,," \
  -n "MyApp Self-Signed CA" \
  -i /opt/easywebserver/certs/myapp.crt

# 3. Проверить
certutil -d sql:$HOME/.pki/nssdb -L

# 4. Перезапустить Chrome/Edge
```

`-t "C,,"` — флаг доверия:

- **C** — доверять как CA для выдачи сертификатов.
- Второй и третий флаг — для S/MIME (не нужно).

**Отзыв:**

```bash
certutil -d sql:$HOME/.pki/nssdb -D -n "MyApp Self-Signed CA"
```

## 7.3. Chrome / Edge на Windows

1. **Win+R** → `certmgr.msc` → Enter.
2. Слева — **Доверенные корневые центры сертификации** → **Сертификаты**.
3. Правый клик по пустому месту → **Все задачи → Импорт**.
4. **Далее** → указать `myapp.crt` → **Далее** → **Далее** → **Готово**.
5. При предупреждении «Не удалось проверить, что сертификат выдан доверенным ЦС» → **Да**.
6. Перезапустить Chrome.

Альтернатива — через `certutil` (Windows):

```cmd
certutil -addstore -f Root "C:\path\myapp.crt"
```

**Отзыв:**

```cmd
certutil -delstore Root "MyApp Self-Signed CA"
```

## 7.4. Firefox (Linux/Windows/macOS)

Firefox **не** использует ни системное хранилище, ни NSS Chrome — у него **свой** профиль NSS. Варианты:

**GUI:**

1. **Настройки → Приватность и защита → Сертификаты → Просмотр сертификатов**.
2. Вкладка **Сертификаты → Импорт** → выбрать `myapp.crt`.
3. Поставить галочку **«Доверять этому сертификату для идентификации веб-сайтов»**.
4. **ОК**, перезапустить Firefox.

**`certutil` — для всех профилей сразу (Linux):**

```bash
for prof in $HOME/.mozilla/firefox/*.default*; do
  certutil -d sql:"$prof" -A \
    -t "C,," \
    -n "MyApp Self-Signed CA" \
    -i /opt/easywebserver/certs/myapp.crt
done
```

**Отзыв:**

```bash
for prof in $HOME/.mozilla/firefox/*.default*; do
  certutil -d sql:"$prof" -D -n "MyApp Self-Signed CA"
done
```

## 7.5. Java-клиент (JVM cacerts)

Java-приложения (не браузер) используют **свой** truststore `$JAVA_HOME/lib/security/cacerts`.

```bash
# Узнать $JAVA_HOME
readlink -f $(which java) | sed 's|/bin/java||'

# Импорт
sudo keytool -importcert \
  -alias myapp \
  -file /opt/easywebserver/certs/myapp.crt \
  -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit \
  -noprompt

# Проверка
keytool -list -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit | grep myapp
```

**Отзыв:**

```bash
sudo keytool -delete -alias myapp -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit
```

## 7.6. Android

1. Скопировать `myapp.crt` на устройство (USB, облако).
2. **Настройки → Безопасность → Шифрование и учётные данные → Установить сертификат → Сертификат CA**.
3. Выбрать файл, подтвердить.
4. **Важно:** на Android 7+ **пользовательские CA** используются браузером Chrome, но **не** некоторыми приложениями (например, Instagram, банковские приложения — у них pinned/system CA). Для тестового стенда обычно достаточно Chrome.

## 7.7. iOS / iPadOS

1. Скопировать `myapp.crt` на устройство (AirDrop, файл).
2. Открыть файл → **Установить профиль** → **Установить** → ввести пароль → **Установить**.
3. **Настройки → Основные → Об этом устройстве → Доверие сертификатам**.
4. Найти `MyApp Self-Signed CA` → включить ползунок.
5. Перезапустить Safari.

---

# 8. Проверка после регистрации на клиенте

## 8.1. `curl`

```bash
# На Linux (после §7.1)
curl -v https://myapp.local:9092/

# Ожидается:
# * SSL certificate verify ok.
# < HTTP/1.1 200 OK

# Без сертификата (проверка SAN)
echo | openssl s_client -connect myapp.local:9092 -servername myapp.local 2>/dev/null | \
  openssl x509 -noout -ext subjectAltName
```

## 8.2. Браузер

Открыть `https://myapp.local:9092/`:

- Должен быть **зелёный замок**, без предупреждений.
- Если предупреждение — **не нажимайте** «Перейти на сайт (небезопасно)»: разберитесь, чего не хватает.

## 8.3. Если браузер всё равно ругается — таблица

| Симптом | Причина | Решение |
|---|---|---|
| `NET::ERR_CERT_AUTHORITY_INVALID` | не в truststore | §7.1–§7.5 |
| `NET::ERR_CERT_COMMON_NAME_INVALID` | SAN не содержит запрошенного hostname | пересоздать с `-ext SAN=dns:...` (§3.2/§3.3) |
| `NET::ERR_CERT_DATE_INVALID` | истёк / системное время сбито | пересоздать; проверить `date` |
| Chrome не видит системный CA (Linux) | Chrome использует NSS | §7.2 |
| Firefox не видит системный CA | Firefox использует свой NSS | §7.4 |
| Java-клиент отказывает | не добавлен в `cacerts` | §7.5 |
| Android-приложение отказывает | pinned cert / system CA | не решается для user CA |
| iOS Safari ругается | не включено полное доверие | §7.7, шаг 3 |
| `Remote host terminated the handshake` | клиент отверг серт | перепроверить SAN + truststore |

---

# 9. Обновление самоподписанного сертификата

## 9.1. Когда нужно

- **Истёк срок** (при `-days 3650` — через 10 лет, но лучше ставить напоминание).
- **Изменился IP/домен** — нужен новый SAN.
- **Изменился пароль keystore** (гигиена).

## 9.2. Что делать

1. **Создать новый** `keystore.p12` (§3.2) с нужным SAN.
2. **Заменить файл на сервере** `/opt/easywebserver/certs/keystore.p12`.
3. **Перезапустить EasyWebServer** — `SSLManager` читает сертификат **только при старте**:

   ```bash
   sudo systemctl restart easywebserver
   # или Ctrl+C и снова запустить
   ```

4. **Экспортировать новый `.crt`** (§3.6).
5. **Обновить на всех клиентах**: удалить старый из truststore, добавить новый (§7).
    - Старый лучше удалить, иначе накопится мусор.
6. **Проверить** `openssl s_client` и браузером.

## 9.3. Автоматизация (systemd)

Если сертификат часто меняется, удобно добавить `ExecReload` в unit-файл:

```ini
[Service]
ExecReload=/bin/kill -HUP $MAINPID
```

`EasyWebServer` не обрабатывает `HUP` — перечитывание всё равно через `restart`:

```bash
sudo systemctl restart easywebserver
```

---

# 10. Проброс портов на роутере (если сервер за NAT)

Если сервер не с белым IP, а за роутером — до сервера должны доходить внешние запросы. Настройте **Port Forwarding**:

| Внешний порт | Внутренний IP | Внутренний порт |
|---|---|---|
| **443** | `192.168.1.42` (ваш сервер) | **9092** |
| **9092** | `192.168.1.42` | **9092** |

**Почему 443 → 9092:** браузер при `https://myapp.local/` (без порта) всегда идёт на TCP 443. Порт 443 на сервере никто не слушает (Java слушает 9092). Проброс пересылает пакеты на 9092, где TLS-сервер их принимает.

**Не пробрасывайте 80 → 9092** — plain HTTP на TLS-порт даст `SSLException` и «Без названия» в браузере.

Проверка с внешней машины:

```bash
nc -vz smwrap.ru 443
nc -vz smwrap.ru 9092
```

---

# 11. Безопасность самоподписанного сертификата

## 11.1. Когда можно

- ✅ Локальная разработка (`localhost`, `myapp.local`).
- ✅ Внутренняя сеть (LAN, VPN), где **все** клиенты — под вашим контролем.
- ✅ Тестовый стенд за VPN.

## 11.2. Когда нельзя

- ❌ Публичный сайт в интернете.
- ❌ Где клиенты не под вашим контролем (публичные пользователи).
- ❌ Если предполагается MitM-угроза — self-signed сам по себе не защищает от неё.
- ❌ Платёжные системы, банковские API — требуют публичный CA.

Для публичного сайта используйте **Let's Encrypt** (см. `Настройка_HTTPS_и_SSL-сертификата_для_FileServer.MD`).

## 11.3. Правила

- **Приватный ключ** — `chmod 600`, владелец — пользователь сервиса. Никогда не передавайте.
- **`keystore.p12`** — тоже `600`.
- **Пароль** — не `changeit` на проде. Хранить в `EnvironmentFile` systemd, Vault, secret-manager.
- **Не коммитить** `keystore.p12` в git. Добавьте в `.gitignore`.
- **Срок действия** — не бесконечный. Ставьте напоминание за месяц до истечения.
- **Удаляйте** старые сертификаты из truststore после обновления.

---

# 12. Резюме — чек-лист

## 12.1. Установка

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk openssl libnss3-tools ca-certificates curl net-tools dnsutils
```

## 12.2. Создание сертификата

```bash
sudo mkdir -p /opt/easywebserver/certs
sudo chown -R $USER:$USER /opt/easywebserver/certs
chmod 700 /opt/easywebserver/certs

keytool -genkeypair \
  -alias server \
  -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -validity 3650 \
  -storetype PKCS12 \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass 'changeit' -keypass 'changeit' \
  -dname "CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU" \
  -ext "SAN=dns:myapp.local,dns:www.myapp.local,dns:localhost,ip:127.0.0.1,ip:192.168.1.42"

chmod 600 /opt/easywebserver/certs/keystore.p12

# Проверка
keytool -list -v -keystore /opt/easywebserver/certs/keystore.p12 -storepass 'changeit'

# Экспорт для клиентов
keytool -exportcert -alias server \
  -keystore /opt/easywebserver/certs/keystore.p12 -storepass 'changeit' \
  -rfc -file /opt/easywebserver/certs/myapp.crt
```

## 12.3. Reg.ru — DNS

1. Войти в `https://www.reg.ru/user/domain_list`.
2. **Управление** → **DNS-серверы и управление зоной**.
3. NS-серверы: `ns1.reg.ru`, `ns2.reg.ru`.
4. Добавить записи:
    - `A @ 203.0.113.10` (внешний IP сервера/роутера).
    - `A www 203.0.113.10` (или `CNAME www smwrap.ru.`).
    - `AAAA @ 2001:db8::1` — если есть IPv6.
5. TTL: **300** (для тестов).
6. Сохранить. Подождать 5–30 минут.

**TXT `_acme-challenge` — НЕ НУЖНЫ** для self-signed.

## 12.4. Проверка DNS

```bash
dig +short A smwrap.ru          # 203.0.113.10
dig +short A www.smwrap.ru      # 203.0.113.10
dig +short NS smwrap.ru         # ns1.reg.ru. ns2.reg.ru.
```

## 12.5. EasyWebServer

```java
web.config("DEFAULT_HOST", "0.0.0.0");
web.config("DEFAULT_PORT", "9092");
web.config("HTTPS_ENABLED",           "true");
web.config("HTTPS_KEYSTORE",          "/opt/easywebserver/certs/keystore.p12");
web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");
```

Запуск и проверка лога — `Issuer : CN=myapp.local...` (= Subject) — self-signed загружен.

## 12.6. Регистрация на клиентах

- **Linux** — `update-ca-certificates` (§7.1).
- **Chrome Linux** — `certutil -A -t "C,,"` (§7.2).
- **Windows** — `certmgr.msc` → Доверенные корневые ЦС (§7.3).
- **Firefox** — свой NSS (§7.4).
- **Java** — `keytool -importcert ... $JAVA_HOME/lib/security/cacerts` (§7.5).
- **Android** — Установка сертификата CA (§7.6).
- **iOS** — Установка профиля + доверие (§7.7).

## 12.7. Итог

- `https://myapp.local:9092/` — работает.
- `https://myapp.local/` (без порта) — после проброса `443 → 9092` на роутере.
- Chrome/Firefox/Java/Android — зелёный замок, без предупреждений.
- Приватный ключ `keystore.p12` — `600`, пароль не `changeit` на проде.
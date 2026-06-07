# Bridge mode — план перехода

Базовый принцип: **армогрэм остаётся полноценным SMS-клиентом**. Bridge-режим
добавляется *поверх* обычной работы. Когда bridge включён — SMS с одного
конкретного gateway-номера парсятся как wire-фреймы и рендерятся как
виртуальные Telegram-style чаты, ключённые по alias. Всё остальное (SMS от
друзей, банков, доставки) работает как в стоковом QKSMS.

## Текущее состояние (после commit 8c0febf8)

| Слой | Файл | Что сделано |
|---|---|---|
| Wire codec | `common/.../bridge/Wire.kt` | Port Go-кодека. encode/decode фреймов, MsgLine, chunk |
| Storage abstraction | `domain/.../bridge/BridgeRepository.kt` | Интерфейс |
| Storage impl | `data/.../bridge/BridgeRepositoryImpl.kt` | Виртуальные Conversation/Recipient/Message в Realm; SmsManager-диспатч |
| Receive hijack | `domain/.../interactor/ReceiveSms.kt` | Если from = gateway → routeIncoming; иначе fallback |
| Send hijack | `data/.../repository/MessageRepositoryImpl.kt` | threadId < 0 → bridgeRepository.sendVirtual |
| List filter | `data/.../repository/ConversationRepositoryImpl.kt` | Скрытие gateway-thread из conversation list |
| Settings | `presentation/.../feature/bridge/BridgeSettingsActivity.kt` | Запускается через `adb shell am start` |
| Prefs | `domain/.../util/Preferences.kt` | `bridgeEnabled`, `bridgeGatewayPhone`, `bridgeOutSeq` |

## Что уже работает (теоретически — нужно проверить на железе)

- SMS от друга → обычный QKSMS-thread, виден в списке как раньше.
- SMS с gateway, парсится как фрейм `#42 msg >abc: hi` → создаётся виртуальный чат `abc`, фрейм НЕ попадает в обычный SMS-thread gateway-номера.
- Ответ в виртуальном чате → wire-encode → SMS на gateway.
- Conversation list скрывает thread gateway-номера если bridge включён.

## Что НЕ работает / открытые вопросы

### 1. Не-фрейм SMS от gateway

Если на gateway-номер вдруг придёт обычная SMS (например, оператор шлёт «баланс
100р»), сейчас:
- `Wire.decode()` вернёт `null` → bridge скажет «не моё» → нормальный путь.
- Но мы СКРЫЛИ gateway-thread из conversation list → пользователь это сообщение
  не увидит.

**Решения:**
- (а) Не скрывать gateway-thread, оставить видимым; виртуальные чаты живут параллельно.
- (б) Скрывать gateway-thread, но в виртуальный системный чат `_sys` дублировать любые не-фреймы от gateway.
- (в) Гибрид: видим gateway-thread только когда туда падает не-фрейм, иначе скрыт.

Рекомендую **(б)** — самый предсказуемый UX, никаких сюрпризов в общем списке.

### 2. Идентификация виртуальных чатов

Сейчас `Recipient.address = alias` (например `"abc"`), `getDisplayName()` пробует
форматировать как телефон → возвращает буквенный alias как есть. Это работает, но:
- Нет различения «реальный контакт» vs «alias-from-bridge» в UI.
- Имя на backend известно (`@username`, «Vasya»), но мы его не сохраняем.

**Решения:**
- Расширить `Wire.Kind.NEW` фрейм чтобы backend слал «alias=abc display=Vasya»;
  сохранять в Realm-таблице `AliasContact { alias, displayName, gateway }`.
- В `Recipient.getDisplayName()` или через расширение проверять alias-таблицу первой.

### 3. Команды / approval flow

Сейчас Phone2 не отправляет `/wl`, `/approve`, `/block`. Подходы:
- (а) Минимально — добавить debug-меню в conversation toolbar: «Bridge → approve / block / whitelist add / hist».
- (б) Полноценно — рендерить `?NEW` фрейм как карточку с тремя кнопками внутри чата `_sys`.

### 4. Chunked / multipart фреймы

Если backend пришлёт `#42.1/3 msg >abc: ...`, `#42.2/3 ...`, `#42.3/3 ...` —
сейчас они инсертятся как 3 отдельных сообщения. Нужен:
- Буфер `(seq_base, total) → received_chunks[]` в `BridgeRepositoryImpl`.
- Склейка когда `received_chunks.size == total`.
- TTL чтобы недополученные части не висели вечно.

### 5. Media

Не делаем в первой фазе. Backend будет слать text-плейсхолдеры (`[photo]`,
`[voice 0:12: распознанный текст]`). Кладём как обычный текст сообщения.

### 6. Активный чат / семантика ответа

Backend поддерживает «активный alias» (`/sw a3` команда) чтобы можно было слать
просто текст без префикса. На стороне телефона это не нужно — каждый чат уже
имеет свой alias, отправка всегда attribuited.

### 7. Cross-mode UX consistency

Виртуальный bridge-чат и обычный SMS-чат должны выглядеть **одинаково** в
conversation list и conversation view. Никакого «специального режима». Это уже
так — мы используем те же Realm-модели.

Единственное визуальное отличие, которое имеет смысл: маленькая иконка/badge
рядом с alias-чатами чтобы пользователь понимал «это не SMS, это TG через bridge».

### 8. Дедупликация входящих

Сейчас inbox только Realm — нет защиты от повторов (если gateway по ошибке
переотправит фрейм). Нужно добавить таблицу `bridge_in_seq { seq PK, received_at }`
с дедупом аналогично backend'у.

### 9. Outbound delivery tracking

Сейчас `sendVirtual` шлёт SMS без PendingIntent для SMS_SENT / SMS_DELIVERED.
Если оператор отверг — пользователь не узнает. Нужно подключить
`SmsSentReceiver` / `SmsDeliveredReceiver` (они есть в QKSMS) и обновлять
Message.boxId соответственно.

### 10. Безопасность

`isGateway` сейчас простое equals по нормализованному номеру. Спуфинг SMS
теоретически возможен. Минимально достаточно. Опциональный апгрейд — HMAC
suffix во фрейме, секрет в prefs (shared с backend).

---

## Фазы

### Фаза 1: «Просто работает» (MVP — сделано)

Сделано в коммитах `24078628` и `8c0febf8`. Требует ручного тестирования на
железе.

### Фаза 2: Дуализм SMS (сейчас)

- [ ] **Решить (1)** — какое поведение для не-фреймов от gateway. Я предлагаю (б).
- [ ] **Дедуп (8)** — `bridge_in_seq` таблица.
- [ ] **Delivery tracking (9)** — PendingIntent с SmsSentReceiver.
- [ ] **Multipart (4)** — буфер chunked фреймов.

### Фаза 3: UX polish

- [ ] **AliasContact (2)** — таблица alias → displayName, заполняется из `?NEW` фреймов.
- [ ] **Badge виртуального чата (7)** — мелкий индикатор в ConversationsAdapter.
- [ ] **Settings hook** — пункт «Bridge» в обычном Settings вместо adb-launch.

### Фаза 4: Approval/control flow

- [ ] **`?NEW` рендерится как карточка** с кнопками approve/block.
- [ ] **Тулбар чата** — кнопки whitelist/block/hist для существующего alias-чата.

### Фаза 5: Опционально

- [ ] **HMAC (10)** — подпись фреймов.
- [ ] **Auto-mark-read** — когда виртуальный чат открыт, сообщения помечаются read автоматически (QKSMS уже умеет — проверить что работает с отрицательными threadId).
- [ ] **Архив / pin** — что произойдёт если пользователь архивирует виртуальный чат? Сейчас работает на общих Conversation полях, должно автоматом, но надо проверить.

---

## Acceptance-критерии MVP (до Фазы 3)

1. **Обычный SMS от друга прилетел** → виден в conversation list, открывается, отвечать можно.
2. **SMS с gateway `#1 msg >abc: hello`** → появился новый чат `abc`, в нём сообщение «hello».
3. **Ответ в чате `abc` «hi»** → на gateway ушёл SMS `#2 msg >abc: hi`.
4. **Чат gateway-номера НЕ виден** в списке (если решение по (1) = вариант б).
5. **SMS на gateway `БАЛАНС 100`** → дублируется в чат `_sys`, в общем списке — нет.
6. **Bridge выключен в settings** → всё работает как стоковый QKSMS, виртуальные чаты исчезают из списка (но данные в Realm сохраняются).

---

## Что НЕ делаем

- Не мигрируем на новые версии Gradle/AGP/Kotlin/Realm — слишком дорого, см. предыдущее обсуждение.
- Не пишем свой Compose-UI поверх QKSMS — переиспользуем существующий.
- Не делаем MMS bridge.
- Не делаем end-to-end encryption.
- Не пытаемся подделать typing/online/read-receipts на стороне Telegram через backend.

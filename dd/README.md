# GFL RU — русификатор Girls' Frontline (часть 1), сервер EN

Пакет игры: `com.sunborn.girlsfrontline.en`. Состоит из двух частей:

- `app/` — Android-лаунчер: ставит перевод, делает бэкап оригиналов, откатывает, запускает игру.
- `tools/gfl_ru_tool.py` — конвейер на Python: вытаскивает тексты из бандлов игры, собирает патч.

## 1. Сделать патч (на ПК)

    pip install UnityPy
    # скопируйте с телефона Android/data/com.sunborn.girlsfrontline.en/files в ./files
    python tools/gfl_ru_tool.py scan    files                      # где лежат тексты и шрифты
    python tools/gfl_ru_tool.py strings files -o strings.tsv       # список строк для перевода
    # переведите второй столбец strings.tsv (теги и плейсхолдеры не трогать)
    python tools/gfl_ru_tool.py verify  strings.tsv                # проверка тегов, плейсхолдеров, ключей
    python tools/gfl_ru_tool.py build   files strings.tsv -o patch.zip --font NotoSans-Regular.ttf --version 1

`strings` можно перезапускать после обновления игры: готовый перевод в `strings.tsv` сохраняется,
добавляются только новые строки. Если бандлы зашифрованы, добавьте `--key <ключ>`.
Если шрифт игры — TextMeshPro, простая замена TTF не поможет и нужен font asset с кириллицей.

## 2. Собрать APK

Положите `patch.zip` в `app/src/main/assets/patch.zip` — перевод будет встроен в APK
(кнопка «Установить встроенный перевод»). Без встроенного патча перевод ставится по https-ссылке
или из zip-файла.

- Без Android Studio: залейте эту папку в репозиторий GitHub — Actions соберёт APK
  (Actions → build-apk → Artifacts → GflRuLauncher-debug).
- В Android Studio: откройте папку, Build → Build APK(s).

## 3. Установить на телефон

- Android 11–12: «Выбрать папку игры (SAF)», затем «Установить встроенный перевод».
- Android 13+: SAF к Android/data закрыт системой. Нужен root (переключатель в приложении) или ПК:
  `adb push <файл> /sdcard/Android/data/com.sunborn.girlsfrontline.en/files/<путь из manifest.json>`

Формат патча: `manifest.json` с `{"version":1,"files":[{"src":"...","dst":"..."}]}` + файлы;
`dst` относительно `Android/data/com.sunborn.girlsfrontline.en/files/`. Сообщество ставит патчи EN-клиента
в `files/Android` с перезаписью, поэтому `dst` обычно начинается с `Android/`.

## Что не проверено
Сборка APK и работа `gfl_ru_tool.py` на реальных бандлах игры не запускались (в среде разработки
не было сети и файлов игры). Протестирована только логика строк: `python tools/test_gfl_ru_tool.py`.

Распространяйте только патч, без файлов игры. Модификация клиента может нарушать правила сервиса.

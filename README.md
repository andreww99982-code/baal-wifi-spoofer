# WifiSpoofer — LSPosed-модуль (Android 10–15)

Сборка: открыть папку в Android Studio (Ladybug+, JDK 17) → Build → Build APK(s).
Локально APK собирается командой `./gradlew assembleDebug`. В GitHub Actions сборка
проверяется на Node.js 20 и 24; Node нужен только для совместимости CI, приложение
собирается Gradle под JDK 17.

Установка: APK → LSPosed → включить модуль → выбрать scope → перезагрузка → в приложении «Синхронизировать эфир».

## Что исправлено по сравнению с исходным гайдом
- settings.gradle.kts: dependencyResolution → dependencyResolutionManagement
- MODE_WORLD_READABLE (крашит на targetSdk ≥ 24) → MODE_PRIVATE + meta-data `xposedsharedprefs` и xposedminversion=93 (нужно LSPosed для XSharedPreferences)
- Убраны package= из манифеста, makeWorldReadable(), зависимость org.json, лишний JSON-файл
- Добавлен app/proguard-rules.pro
- Сетевые вызовы в WifiDataFetcher — обычные функции (вызываются из Dispatchers.IO)
- WifiSsid: fromBytes вместо несуществующего createFromByteArray
- item_app.xml: высота wrap_content (двухстрочный текст)

## Сборка через GitHub Actions
1. Создайте репозиторий на GitHub, залейте содержимое этой папки (включая скрытую .github).
2. Вкладка Actions → Build APK (запускается автоматически или кнопкой Run workflow).
3. По завершении откройте запуск → раздел Artifacts → WifiSpoofer-debug-apk.

## Фикс: "no cached networks, passing through" в логах
Причина — SharedPreferences писались с MODE_PRIVATE, из-за чего XSharedPreferences
в хуке видел пустой/недоступный файл. Правильный режим — MODE_WORLD_READABLE:
именно на этот флаг LSPosed реагирует и временно снимает системную блокировку
(ContextImpl.checkMode), делая файл читаемым для модуля. Это описано в
LSPosed Wiki: "New XSharedPreferences" (нужен xposedminversion >= 93 или
meta-data xposedsharedprefs=true — уже добавлено в манифест).

Если после исправления и синхронизации в логах LSPosed всё ещё "no cached
networks" — есть риск, что ваша версия LSPosed (2.2.0+) уже убрала этот
механизм (см. issue LSPosed/CorePatch#159). На этот случай в проект добавлен
запасной канал: ConfigProvider — обычный экспортируемый ContentProvider,
хуки автоматически падают на него, если XSharedPreferences вернул пустоту.
Он работает через стандартный Binder IPC и не зависит от версии LSPosed.

Как проверить, что дело было именно в правах на файл: откройте приложение
WifiSpoofer, оно теперь при старте пишет в logcat строку вида
`prefs file: /data/data/com.wifispoofer/shared_prefs/wifispoofer_prefs.xml,
exists=true, perms=rw` — если exists=false, модуль либо выключен в LSPosed,
либо ещё не было ни одной перезагрузки после включения.

## Диагностика для конкретной прошивки (Pixel 7 / Evolution X)
Добавлено подробное логирование, потому что на этом устройстве полей
ScanResult, переименованных вендором, скорее всего нет (Evolution X — почти
чистый AOSP), а значит проблема в другом. После пересборки и установки
проверьте в логах LSPosed конкретно для процесса WiFi Analyzer:

1. `🎯 Hooking package: ... (pid=..., process=...)` — модуль реально
   загрузился в этот процесс. Если строки нет вообще — приложение не было
   по-настоящему перезапущено (force-stop + открыть заново, простого
   "очистить кэш" может быть недостаточно, оно не всегда убивает процесс).
2. `getScanResults() CALLED in process <pid>` — эта строка обязана появляться
   при КАЖДОМ реальном вызове метода из приложения. Если её нет вообще,
   когда вы открываете/обновляете список сетей в WiFi Analyzer — значит
   вызов идёт не через WifiManager.getScanResults() (маловероятно для
   обычных сторонних анализаторов, т.к. WifiScanner требует системных прав),
   либо это другой процесс/классlоader.
3. `getScanResults: injected N fake networks` — если N=0 при непустом кэше,
   смотрите строки `Failed to create ScanResult for ...` и
   `instantiateScanResult: ... не сработал` — они укажут, какой именно
   способ создания/заполнения объекта не прошёл на этой прошивке.

Код теперь пробует 3 разных способа создать пустой ScanResult (обычный
конструктор → Unsafe.allocateInstance → любой доступный конструктор с
нулевыми аргументами) и логирует каждое поле отдельно, вместо того чтобы
проваливать всю сеть при первой же неудаче.

## Важное обновление: XSharedPreferences подтверждённо не работает на вашем устройстве
В логе LSPosed на Pixel 7 / Evolution X видно:
`⚠ Cannot read prefs file: /data/misc/<uuid>/prefs/com.wifispoofer/wifispoofer_prefs.xml`
— это путь внутреннего хранилища механизма "new XSharedPreferences" самого
LSPosed, и он оказался нечитаем даже для com.google.android.gms, у которого
все хуки исправно встали. Значит проблема не в scope и не в конкретном
приложении — сам механизм чтения не работает на этой связке LSPosed/прошивки
(в новых версиях LSPosed от него вообще отказываются).

Модуль переключён на ContentProvider (com.wifispoofer.config) как ОСНОВНОЙ
канал передачи данных между приложением и хуками — это обычный Binder IPC,
не зависящий от XSharedPreferences. XSharedPreferences оставлен вторым
резервным вариантом на случай других устройств.

После установки этой версии в логе должны появляться строки:
- `loadScope: получено через ContentProvider: [...]`
- `loadCachedNetworks: получено через ContentProvider (N)`

Если вместо этого видите `ContentProvider не сработал: ...` — пришлите точный
текст ошибки, вероятно потребуется донастройка прав провайдера.

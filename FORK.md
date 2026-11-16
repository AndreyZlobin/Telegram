# Telegram Android — подготовка форка

Upstream: https://github.com/DrKLO/Telegram

Fork: https://github.com/AndreyZlobin/Telegram

База: Telegram 13.2.2 (7303), commit `21f7d109da6ab3506c9e9577f98c6a38771ab335`.
Изменений поведения и интерфейса пока нет. Ветка `setup/android-build` содержит только подготовку сборки.

## Окружение

- JDK 17.
- Android SDK Platform 36 и Build Tools 36.0.0.
- Android NDK 27.2.12479018 и CMake 3.22.1.
- Gradle 8.13 загружается штатным Gradle Wrapper.

В `local.properties` укажите `sdk.dir=/абсолютный/путь/к/Android/sdk`.
Переменная `JAVA_HOME` должна указывать на JDK 17.
Локальные пути также можно сохранить в `.local/env.sh`; каталог исключён из Git.

## Сборка

```sh
git clone --branch setup/android-build --recursive --shallow-submodules https://github.com/AndreyZlobin/Telegram.git
cd Telegram
./scripts/build-debug.sh
```

По умолчанию собирается ARM64 debug APK для современного Android-телефона.
Результат: `build/fork-debug/telegram-fork-arm64-v8a-debug.apk`.
Журнал: `build/fork-debug/build.log`.
Сборку другой архитектуры можно выбрать через `BUILD_ABI=x86_64`.

Это базовая тестовая сборка с опубликованной upstream тестовой конфигурацией API,
Firebase и подписи. Личный API ID, Firebase и отдельная подпись ещё не настроены.
Вход в аккаунт и push-уведомления не проверялись. Для распространения приложения
нужны собственные значения согласно upstream README.

## Обновление базы

```sh
git remote add upstream https://github.com/DrKLO/Telegram.git
git fetch upstream master
```

Сначала интегрируйте изменения upstream, затем обновите submodules до версий,
закреплённых в новом коммите. Не используйте `git submodule update --remote`:
это изменит версии зависимостей относительно официальной сборки.

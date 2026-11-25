# VideoGrab (репозиторий Gggg)

Android-приложение: кидаешь ссылку на видео с любого сайта — оно находит видео, показывает превью
и качает в 1080p или в любом формате, который отдал сайт.

## Что внутри
- Под капотом **yt-dlp** (библиотека `youtubedl-android 0.18.1` + python3 + ffmpeg), то есть работают
  тысячи сайтов: YouTube, Rutube, VK, TikTok, Twitter/X, Reddit, Vimeo, Dailymotion, m3u8/mp4-прямые ссылки.
- **Сверху самые популярные форматы** (чипы): `1080p MP4`, `1080p без звука`, `720p MP4`, `480p MP4`,
  `360p MP4`, `MP3`, `M4A`.
- Ниже — список форматов: только **mp4 по убыванию качества до 1080p**, внизу пара звуковых
  дорожек. Мусор (`webp`, `mhtml`, картинки-превью) и 4K/8K в списке не показываются.
- Превью видео (thumbnail), название, автор, длительность.
- Качает foreground-сервис: прогресс в шторке, там же кнопка «Стоп».
- Готовый файл попадает в `/sdcard/Download/VideoGrab/` (через MediaStore, разрешения на память не нужны).
- Ловит «Поделиться» из браузера или YouTube — ссылка подставляется автоматически.

## Сборка
Локальный тулчейн не нужен, APK собирает GitHub Actions (`.github/workflows/build.yml`):

1. Пуш в `main` **или** вкладка Actions → workflow `build-apk` → `Run workflow`.
2. Через 3–6 минут APK лежит:
   - в **Releases** (самый простой путь с телефона — asset `app-debug.apk`),
   - и в **Artifacts** рана (`VideoGrab-debug-apk`).

Первый прогон ещё и «материализует» исходники в репозиторий (шаг `Commit sources back`), так что
дальше править код можно прямо на GitHub.

Ручная сборка (если есть Android Studio / gradle 8.7 + JDK 17 + Android SDK 34):
```
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Установка на телефон
Скачать `app-debug.apk` → открыть → разрешить установку из этого источника.
Первый запуск: 5–15 секунд распаковываются python, ffmpeg и yt-dlp, потом можно кидать ссылку.

## «Ключ» для правки репозитория
Токен GitHub нельзя сгенерировать за тебя — он выдаётся только в твоём аккаунте:
GitHub → аватар → Settings → Developer settings → Personal access tokens → **Tokens (classic)** →
Generate new token (classic) → scopes `repo` + `workflow` → Generate → скопировать `ghp_...`.

Использование:
```
git clone https://kaptolli:ТОКЕН@github.com/kaptolli/Gggg.git
cd Gggg
git add -A && git commit -m "update" && git push
```
Или один раз запомнить креды: `git remote set-url origin https://github.com/kaptolli/Gggg.git`
(при пуше логин `kaptolli`, пароль — токен). Токен = пароль ко всему аккаунту: не кидай его в чаты,
не коммить в код; если утёк — сразу Revoke на той же странице.

## Только для личного использования
Качаешь то, что имеешь право качать; YouTube и прочие площадки такое не любят.

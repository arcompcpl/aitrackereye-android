# AiTrackerEye Konfigurator – Android 0.4.1-dev

Aplikacja na **telefony i tablety z Androidem 7.0 lub nowszym** do konfiguracji kamery wideorejestracji (BCS/Dahua) przez sieć Wi-Fi/LAN. Odpowiednik programu Windows *AiTrackerEye Konfigurator 0.4.1-dev* – ta sama logika ustawień (mapa pól w `docs/API_MAPA.md` wersji Windows).

## Pobranie i instalacja

1. Na telefonie otwórz **[Releases → najnowsza](../../releases/latest)** i pobierz plik `AiTrackerEye-….apk`.
2. Otwórz pobrany plik. Za pierwszym razem Android zapyta o zgodę na instalację z tego źródła (przeglądarka / Pliki) – zezwól.
3. Kolejne wersje instalują się jako aktualizacja (ten sam klucz podpisu), ustawienia zostają.

Każda zmiana w gałęzi `main` automatycznie buduje nowe APK (zakładka **Actions** → „Budowanie APK”).

## Obsługa

- Telefon połącz z tą samą siecią co kamera. Wpisz **IP, login, hasło** → **Połącz**. Hasło nie jest zapisywane.
- Pasek stanu: zielony – połączono, czerwony – brak połączenia, pomarańczowy – trwa operacja; po prawej ping do kamery.
- **Telefon:** zakładki u góry (przewijane), osobna zakładka *Podgląd*. Po połączeniu karta połączenia się zwija („Zmień”, by ją rozwinąć).
- **Tablet (poziomo):** zakładki z boku, podgląd na żywo stale po prawej (jak w wersji Windows).

| Zakładka | Zawartość |
|---|---|
| Nagrywanie | maks. długość pliku, nagrywanie wstępne, tryb, strumień nagrań |
| Harmonogram | liczba i łączny czas nagrań na karcie; tydzień w podglądzie; do 6 okresów na dzień z przełącznikiem Wł./Wył.; kopiowanie dnia |
| Pamięć | zapis nagrań, pełna karta, stan karty SD, formatowanie |
| Obraz | napis na obrazie, strumień główny i podstrumień, znak wodny |
| Data i czas | zegar kamery, ręcznie/NTP, synchronizacja z telefonem, format, strefa, czas letni |
| Odtwarzanie | kalendarz z kropkami w dniach z nagraniami, lista nagrań, odtwarzanie z przewijaniem, pobieranie zaznaczonych (.dav) |
| Podgląd | obraz na żywo RTSP (strumień główny / podstrumień) |

**Menu (⋮):** eksport i import konfiguracji (.json – zgodne z wersją Windows), *Wyślij diagnostykę* (plik `diagnostyka.txt` bez haseł – do analizy), *Wyślij dziennik*, O programie.

**Pobieranie nagrań:** przy pierwszym pobraniu wybierz folder (np. *Movies/AiTrackerEye*). Pliki trafiają do `folder / napis na obrazie / RRRR-MM-DD / RRRR-MM-DD_GG-MM-SS_GG-MM-SS.dav`. Pliki .dav odtwarza np. VLC.

## Bezpieczeństwo

- Hasło nie jest zapisywane ani w ustawieniach, ani w dzienniku.
- Przed każdym zapisem kopia odczytanej konfiguracji trafia do pamięci aplikacji (`backups`, 50 ostatnich).
- Zapisywane są tylko pola, które kamera zwróciła i które zmienił użytkownik. Formatowanie karty i import wymagają potwierdzenia.

## Budowanie samodzielnie

Android Studio (Ladybug lub nowsze) → *Open* → ten folder → *Run*. Albo z wiersza poleceń (JDK 17):

```
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Struktura kodu

| Plik | Odpowiednik w wersji Windows |
|---|---|
| `model/Model.kt` | `model.py` |
| `camera/CameraCore.kt`, `camera/CameraApi.kt` | `camera_api.py` (HTTP Digest, CGI) |
| `util/Net.kt` | `netcheck.py` |
| `MainViewModel.kt` | logika `app.py` (połączenie, zapis, ping, podgląd, pobieranie) |
| `ui/tabs/*.kt` | `tab_*.py` |
| `app/src/test/` | `tests/` (te same przypadki + atrapa kamery z weryfikacją Digest) |

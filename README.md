# CadenceToFootpodAndroid 0.5.0

Android bridge dla MyWhoosh:

1. odbiera kadencję z czujnika BLE CSC albo smart trenażera (KICKR / FTMS / Cycling Power),
2. z trenażera KICKR / FTMS odczytuje również moc chwilową,
3. może wystawić kadencję jako BLE Running Speed and Cadence / Footpod (RSC 0x1814),
4. wystawia kontroler OpenBikeControl po mDNS + TCP,
5. automatycznie zmienia wirtualny bieg MyWhoosh na podstawie kadencji,
6. ma tryb **Power Target**, który dobiera przełożenia tak, aby dążyć do zadanej mocy i kadencji,
7. obsługuje zewnętrzny pilot / kontroler Bluetooth HID z mapowaniem przycisków na **Bieg +1 / Bieg −1**.

## Karty aplikacji

### Połączenie

Skanowanie i wybór źródła telemetrii, Footpod oraz OpenBikeControl.

Obsługiwane źródła:

- CSC `0x1816`,
- FTMS `0x1826`,
- Cycling Power `0x1818`,
- urządzenia KICKR.

Dla KICKR CORE 2 aplikacja preferuje FTMS / Indoor Bike Data `0x2AD2`.
Z FTMS odczytywane są:

- Instantaneous Cadence,
- Instantaneous Power.

Przy fallbacku Cycling Power Measurement `0x2A63` moc jest pobierana bezpośrednio z pola Instantaneous Power, a kadencja z opcjonalnych danych obrotu korby.

### Rower

Klasyczny AutoShift oparty o kadencję.

Domyślnie:

- kadencja docelowa: `85 RPM`,
- histereza: `±5 RPM`,
- strefa bez zmiany biegu: `80–90 RPM`,
- zwłoka zwykłej korekty: `1.5 s`,
- cooldown po zmianie: `3.0 s`,
- gwałtowna zmiana: od `10 RPM` w oknie `1.5 s`,
- szybka korekta: `1 bieg / 5 RPM`, maksymalnie `±5 biegów`.

### Moc

Nowy regulator **Power Target**.

Domyślne ustawienia:

- moc docelowa: `200 W`,
- histereza mocy: `±10 W`,
- kadencja docelowa: `85 RPM`,
- histereza kadencji: `±5 RPM`,
- zwłoka przed zmianą: `2.0 s`,
- cooldown: `4.0 s`.

Dostępne przyciski:

- **Moc −5 W** / **Moc +5 W**,
- **Kadencja −1** / **Kadencja +1**,
- **Bieg −1** / **Bieg +1**,
- **Target mocy WŁ./WYŁ.**.

Regulator działa jako sprzężenie zwrotne:

- moc za niska -> preferuje cięższy bieg (`Shift Up`),
- moc za wysoka -> preferuje lżejszy bieg (`Shift Down`),
- kadencja za wysoka -> preferuje cięższy bieg,
- kadencja za niska -> preferuje lżejszy bieg.

Jeżeli moc i kadencja wskazują ten sam kierunek, regulator zmienia bieg po zadanej zwłoce.
Jeżeli wskazują kierunki przeciwne, automat nie zmienia biegu i czeka na zmianę wysiłku zawodnika. Zapobiega to oscylacji między dwoma biegami.

Moc jest wygładzana filtrem wykładniczym, a regulator zmienia tylko jeden bieg naraz. Po zmianie obowiązuje cooldown, po którym wynik jest oceniany ponownie.

> Power Target nie włącza trybu ERG w KICKR. Regulacja odbywa się przez wirtualne biegi OpenBikeControl w MyWhoosh. Dzięki temu profil trasy / tryb SIM pozostaje po stronie MyWhoosh.

Tryby **AutoShift kadencji** i **Power Target** są wzajemnie wykluczające się — włączenie jednego wyłącza drugi.

### Kontroler

Karta **Kontroler** służy do obsługi zewnętrznego pilota Bluetooth działającego w Androidzie jako urządzenie HID (np. klawiatura, pilot multimedialny, D-pad lub gamepad).

Konfiguracja:

1. wybierz **Otwórz ustawienia Bluetooth / sparuj pilot**,
2. sparuj i połącz pilot z Androidem,
3. wróć do aplikacji i sprawdź, czy pojawił się na liście aktywnych kontrolerów,
4. wybierz **Naucz Bieg +** i naciśnij wybrany przycisk na pilocie,
5. wybierz **Naucz Bieg −** i naciśnij drugi przycisk.

Mapowanie jest zapisywane w `SharedPreferences` i działa na wszystkich kartach aplikacji. Zapisywany jest kod klawisza oraz descriptor urządzenia, dlatego przycisk o tym samym kodzie z innego urządzenia nie powinien wywołać zmiany biegu. Przytrzymanie przycisku nie generuje serii zmian — jedna fizyczna akcja daje jedną zmianę biegu.

> Kontroler musi być widziany przez Android jako urządzenie wejściowe HID. Piloty BLE korzystające z własnego, niestandardowego protokołu GATT wymagają osobnej obsługi konkretnego modelu/protokołu.

## Footpod

Po połączeniu źródła kadencji można uruchomić **Wirtualny Footpod**. Aplikacja wystawia RSC `0x1814` i przesyła kadencję oraz emulowaną prędkość.

## OpenBikeControl

Telefon z bridge'em i urządzenie z MyWhoosh muszą być w tej samej sieci Wi-Fi.

Aplikacja reklamuje `_openbikecontrol._tcp` i wysyła:

```text
01 01 01  Shift Up pressed
01 01 00  Shift Up released
01 02 01  Shift Down pressed
01 02 00  Shift Down released
```

## Zalecany test 0.5.0

1. Połącz KICKR CORE 2 i rozpocznij pedałowanie.
2. Sprawdź, czy karta **Moc** pokazuje aktualne `W` oraz `RPM`.
3. Uruchom OpenBikeControl i połącz go z MyWhoosh.
4. Sprawdź ręcznie `Bieg −1` i `Bieg +1`.
5. Ustaw np. `150 W`, `85 RPM`, histerezę `±10 W / ±5 RPM`.
6. Włącz **Target mocy**.
7. Obserwuj status regulatora i log zmian biegów.
8. Przejdź do karty **Kontroler**, sparuj pilot Bluetooth HID i przypisz dwa przyciski.
9. Sprawdź, czy przycisk pilota wykonuje **Bieg +1 / Bieg −1** także po przejściu na kartę Rower lub Moc.
10. Po potwierdzeniu działania stopniowo zawężaj histerezę lub skracaj cooldown.

## Wymagania

- Android 8.0 (API 26) lub nowszy,
- BLE,
- BLE advertising do funkcji Footpod,
- Wi-Fi / sieć lokalna do OpenBikeControl,
- KICKR / FTMS / Cycling Power dla trybu Power Target,
- compileSdk 35,
- targetSdk 35,
- Java 17 / Kotlin.

# CadenceToFootpodAndroid 0.3.0

Android bridge dla MyWhoosh:

1. odbiera kadencję z czujnika BLE CSC albo smart trenażera (KICKR / FTMS / Cycling Power),
2. może wystawić tę kadencję jako BLE Running Speed and Cadence / Footpod (RSC 0x1814),
3. wystawia kontroler OpenBikeControl po mDNS + TCP,
4. automatycznie zmienia wirtualny bieg MyWhoosh na podstawie kadencji,
5. ma osobną kartę **Rower** do sterowania AutoShift podczas jazdy.

## Karta Rower

Domyślnie:

- kadencja docelowa: `85 RPM`,
- histereza: `±5 RPM`,
- strefa bez zmiany biegu: `80–90 RPM`,
- zwłoka zwykłej korekty: `1.5 s`,
- cooldown po zmianie: `3.0 s`,
- gwałtowna zmiana: od `10 RPM` w oknie `1.5 s`,
- szybka korekta: `1 bieg / 5 RPM`, maksymalnie `±5 biegów`.

Na karcie znajdują się przyciski:

- **Kadencja −1** – zmniejsza kadencję docelową o 1 RPM,
- **Kadencja +1** – zwiększa kadencję docelową o 1 RPM,
- **Bieg −1** – ręczny Shift Down,
- **Bieg +1** – ręczny Shift Up,
- przełącznik gwałtownej korekty,
- przełącznik AutoShift.

Przykład szybkiej korekty:

```text
70 -> 96 RPM w około 1.5 s
zmiana: +26 RPM
26 / 5 = 5.2
=> maksymalnie SHIFT UP x5
```

Analogicznie gwałtowny spadek kadencji powoduje serię Shift Down. Zmiany OpenBikeControl są wysyłane sekwencyjnie z krótkim odstępem, aby MyWhoosh nie zgubił kliknięć.

## KICKR CORE 2

Przycisk **Skanuj czujnik / KICKR** wyszukuje:

- CSC `0x1816`,
- FTMS `0x1826`,
- Cycling Power `0x1818`,
- urządzenia o nazwie KICKR.

Dla KICKR CORE 2 aplikacja preferuje FTMS / Indoor Bike Data `0x2AD2`. Jeśli FTMS nie udostępni kadencji, aplikacja próbuje Cycling Power Measurement.

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

Przy szybkiej korekcie te pary są wysyłane kolejno, maksymalnie 5 razy.

## Zalecana kolejność testu 0.3.0

1. Połącz KICKR / czujnik i potwierdź odczyt RPM.
2. Uruchom OpenBikeControl i połącz go z MyWhoosh.
3. Na karcie **Rower** sprawdź `Bieg −1` i `Bieg +1`.
4. Ustaw np. `85 RPM`, histerezę `±5`.
5. Włącz AutoShift.
6. Dopiero po sprawdzeniu zwykłej regulacji włącz / pozostaw włączoną gwałtowną korektę.

## Wymagania

- Android 8.0 (API 26) lub nowszy,
- BLE,
- BLE advertising do funkcji Footpod,
- Wi-Fi / sieć lokalna do OpenBikeControl,
- compileSdk 35,
- targetSdk 35,
- Java 17 / Kotlin.

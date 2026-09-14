# Changelog

## 0.3.0 - 2026-09-14

- dodano dwie karty interfejsu: **Połączenie** i **Rower**,
- przeniesiono ustawienia AutoShift na kartę Rower,
- zmieniono konfigurację z minimum/maksimum na **kadencję docelową + histerezę**,
- dodano przyciski **Kadencja −1** i **Kadencja +1**,
- dodano przyciski **Bieg −1** i **Bieg +1**,
- dodano wykrywanie gwałtownej zmiany kadencji,
- szybka korekta wykonuje proporcjonalnie 1–5 zmian biegów,
- domyślnie 1 bieg przypada na 5 RPM gwałtownej zmiany,
- maksymalna szybka korekta jest ograniczona do ±5 biegów,
- dodano regulowany próg gwałtownej zmiany (domyślnie 10 RPM / 1.5 s),
- dodano możliwość wyłączenia gwałtownej korekty bez wyłączania całego AutoShift,
- OpenBikeControl wysyła wielokrotne zmiany biegów sekwencyjnie, aby ograniczyć gubienie kliknięć,
- rozszerzono log AutoShift o liczbę biegów i przyczynę decyzji,
- podniesiono wersję aplikacji do 0.3.0.

## 0.2.1 - 2026-09-14

- skaner BLE nie ogranicza się już do CSC 0x1816,
- wykrywanie FTMS 0x1826, Cycling Power 0x1818 oraz urządzeń o nazwie KICKR,
- dodany odczyt kadencji z FTMS Indoor Bike Data 0x2AD2,
- fallback do Cycling Power Measurement 0x2A63 z danymi obrotu korby,
- KICKR CORE 2 może być używany bezpośrednio jako źródło kadencji dla Footpoda i AutoShift.

## 0.2.0 - 2026-09-14

- dodano serwer OpenBikeControl po mDNS + TCP,
- dodano ręczne Shift Up / Shift Down,
- dodano AutoShift na podstawie RPM,
- dodano regulowane minimum / maksimum RPM,
- dodano regulowaną zwłokę przed zmianą i cooldown,
- zachowano bridge CSC -> BLE Footpod.

## 0.1.0 - 2026-09-14

- skanowanie czujników BLE CSC,
- odczyt i przeliczanie kadencji z crank revolution data,
- emulacja BLE Running Speed and Cadence / Footpod,
- regulowana emulowana prędkość,
- obsługa uprawnień Bluetooth Android 12+.

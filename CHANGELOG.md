# Changelog

## 0.5.1 — poprawka kompilacji kontrolera

- poprawiono pobieranie urządzeń z `InputManager.inputDeviceIds` (`IntArray`),
- dodano jawny typ `List<InputDevice>`, dzięki czemu Kotlin poprawnie rozpoznaje `isExternal`, `isVirtual`, `supportsSource` i `descriptor`,
- bez zmian w sposobie mapowania przycisków i działania Bieg +/−.

## 0.5.0 - 2026-09-16

- dodano czwartą kartę **Kontroler**,
- dodano obsługę pilotów i kontrolerów Bluetooth HID widocznych w Androidzie jako urządzenia wejściowe,
- dodano otwieranie systemowych ustawień Bluetooth do parowania pilota,
- dodano listę aktywnych zewnętrznych kontrolerów HID,
- dodano tryb nauki przycisków **Bieg +1** i **Bieg −1**,
- mapowanie zapisuje kod klawisza oraz descriptor konkretnego urządzenia w `SharedPreferences`,
- mapowanie działa niezależnie od aktualnie otwartej karty aplikacji,
- długie przytrzymanie przycisku nie powoduje wielokrotnej zmiany biegu,
- przypisany klawisz jest przejmowany przez aplikację, aby nie wykonywać równolegle jego standardowej funkcji systemowej,
- dodano możliwość anulowania nauki i wyczyszczenia mapowania,
- podniesiono wersję aplikacji do 0.5.0.

## 0.4.0 - 2026-09-15

- dodano trzecią kartę **Moc**,
- dodano odczyt Instantaneous Power z FTMS Indoor Bike Data,
- dodano odczyt Instantaneous Power z Cycling Power Measurement,
- dodano nowy `PowerTargetEngine`,
- regulator dąży jednocześnie do zadanej mocy i kadencji przez wirtualną zmianę przełożeń,
- dodano regulowaną moc docelową i histerezę mocy,
- dodano osobną kadencję docelową i histerezę dla trybu Power Target,
- dodano przyciski **Moc −5 W / +5 W**, **Kadencja −1 / +1** i ręczne **Bieg −1 / +1**,
- dodano wygładzanie mocy przed podejmowaniem decyzji,
- regulator zmienia jeden bieg na cykl i ponownie ocenia moc po cooldownie,
- przy konflikcie celu mocy i kadencji regulator czeka zamiast oscylować biegami,
- tryby AutoShift kadencji i Power Target są wzajemnie wykluczające się,
- podniesiono wersję aplikacji do 0.4.0.

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

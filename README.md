# Android-LPC-Flasher

Eine Android-App zum Lesen, Schreiben und Verwalten des Flash-Speichers von NXP LPC11xx Mikrocontrollern über einen USB-Seriell-Adapter (z. B. SBDAP).

## Funktionen

### USB-Verbindung & Geräte-Erkennung
- Automatische Erkennung angeschlossener USB-Seriell-Adapter (CP210x, FTDI FT232, CH340, CH9102, PL2303)
- Automatischer App-Start beim Anschließen eines unterstützten USB-Geräts
- USB-Permission-Handling mit automatischer Abfrage
- Auswahl bei mehreren angeschlossenen Geräten

### ISP-Modus & Chip-Identifikation
- Automatischer Eintritt in den NXP ISP-Bootloader-Modus über DTR/RTS-Signale
- Synchronisation mit dem LPC-Bootloader (115200 Baud, 12 MHz Oszillator)
- Chip-Erkennung anhand der Part-ID (umfangreiche LPC1110/1111/1112/1113/1114/1115-Datenbank)
- Anzeige von Chip-Name, Flash-Größe und RAM-Größe
- Auslesen und Anzeigen der vollständigen 16-Byte Unique-ID (UID)

### Flash-Operationen
- **Firmware schreiben (Flash):** Intel-HEX- und Binärdateien in den Flash-Speicher programmieren
- **Firmware verifizieren (Verify):** Geschriebene Daten mit der Quelldatei abgleichen
- **Flash löschen (Erase):** Gesamten Flash-Speicher löschen
- **Blank-Check:** Prüfen, ob der Flash-Speicher leer ist
- **Flash lesen (Read):** Gesamten Flash-Inhalt auslesen und als Intel-HEX-Datei speichern
- **Hardware-Reset:** Chip über DTR-Signal zurücksetzen (ohne ISP-Modus)

### Firmware-Katalog
- Online-Firmware-Katalog direkt aus dem Selfbus GitHub-Repository
- Kategorisierte Geräte-Übersicht (z. B. Schaltaktoren, Dimmer, Sensoren)
- Firmware-Varianten mit Versions-Informationen und Hinweisen
- Direkter Download und Laden von Firmware aus dem Katalog

### Sicherheitsprüfungen (Pre-Flash Safety Checks)
- Prüfung der Firmware-Größe gegen die Flash-Kapazität des erkannten Chips
- Erkennung von Code Read Protection (CRP1/CRP2/CRP3/NO_ISP)
- ISP-Pin-Prüfung zum Schutz vor versehentlichem Sperren
- Bootloader-Kompatibilitätsprüfung
- Sicherheitsdialog mit Bestätigung bei erkannten Warnungen/Fehlern

### UID-Verwaltung
- Auslesen der vollständigen 128-Bit Chip-UID (16 Bytes)
- Anzeige im Hex-Format (XX:XX:XX:...:XX)
- Kopieren in die Zwischenablage
- Speichern als Textdatei mit Zeitstempel

### Firmware-Dateien
- Laden von Intel-HEX-Dateien (.hex) über den Android-Dateimanager
- Laden von Binärdateien (.bin)
- Anzeige von Dateiname und Firmware-Größe
- Validierung der Firmware-Struktur (max. 2 MB)

### Einstellungen
- Baudrate (Standard: 115200)
- Oszillator-Frequenz (Standard: 12000 kHz)
- ISP-Timing-Parameter (T1, T2, Reset-Dauer, Post-Reset-Delay)
- Auto-Reset nach Flash-Vorgang (ein/aus)
- Boot-Deskriptor-Adresse (manuell konfigurierbar)
- Read-Chunk-Größe (128/256/512 Bytes)

### Benutzeroberfläche
- Material Design 3 (Jetpack Compose)
- Zweisprachig: Deutsch und Englisch (umschaltbar)
- Fortschrittsanzeige bei Flash/Verify/Read-Operationen
- Farbcodiertes Log (Info, Erfolg, Warnung, Fehler, Debug)
- Debug-Log ein-/ausblendbar
- Log in Zwischenablage kopieren oder als Datei speichern

## Unterstützte Hardware

### USB-Seriell-Adapter
| Adapter | VID:PID |
|---------|---------|
| Silicon Labs CP210x | 10C4:EA60 |
| FTDI FT232 | 0403:6001 |
| QinHeng CH340 | 1A86:7523 |
| QinHeng CH9102 | 1A86:55D4 |
| Prolific PL2303 | 067B:2303 |

### Unterstützte Mikrocontroller
- NXP LPC1110, LPC1111, LPC1112, LPC1113, LPC1114, LPC1115
- Inklusive aller bekannten Varianten (FDH, FHN, FBD, FHI, etc.)
- Flash-Größen: 4 KB bis 64 KB
- RAM-Größen: 1 KB bis 8 KB

## Systemanforderungen
- Android 8.0 (API 26) oder höher
- USB-Host-Unterstützung (USB OTG)
- Internetverbindung für den Firmware-Katalog (optional)

## Lizenz
Siehe [LICENSE](LICENSE).

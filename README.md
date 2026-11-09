# Meditate

Android-App zur Meditation: mischt beruhigende Klänge, die endlos laufen.

| Klang | Wie er entsteht |
|---|---|
| 🔥 Lagerfeuer | Fauchen + zufälliges Knistern/Knacken |
| 💧 Wasser (Gebirgsbach) | echte Aufnahme, die alle 10–25 s weich an eine zufällige Stelle überblendet – keine hörbare Schleife |
| 🌬️ Wind | Rauschen durch wandernde Filter, Böen |
| 🎐 Windspiel | 6 pentatonisch gestimmte Röhren (Modalsynthese) |
| 🔔 Klangschalen | 3 Schalen mit schwebenden Obertönen, Anschlag alle 15–30 s |
| 🌲 Wald | echte Vogelgesang-Aufnahme, endlos variiert wie beim Wasser |
| ✨ Sphären | warme Akkordflächen, die langsam ineinander fließen, Schimmertöne, großer Hall |
| 🎵 eigene MP3s | jede Datei in `app/src/main/assets/tracks/` |

Die eingebauten Klänge werden **live berechnet** – es gibt also keine hörbare
Schleife, nichts wiederholt sich exakt, und die APK bleibt klein. Ausnahme: Wasser und Wald
sind echte Aufnahmen (siehe Quellen), die durch zufälliges Überblenden endlos laufen. Eigene MP3s werden
lückenlos geloopt.

Funktionen: Klänge an/aus + eigene Lautstärke, sanftes Ein-/Ausblenden, Schlaf-Timer
(blendet in den letzten 30 s aus), Wiedergabe im Hintergrund mit Benachrichtigung,
Pause bei Anruf/anderer Audio-App und beim Abziehen der Kopfhörer.

## Eigene MP3s hinzufügen

Datei nach `app/src/main/assets/tracks/` legen – der Dateiname wird zum Anzeigenamen
(`Regen am See.mp3` → „Regen am See"). Für nahtlose Loops sollten Anfang und Ende
zueinander passen.

## Release (Installation über Obtainium)

1. Einmalig Repository-Secrets anlegen (*Settings → Secrets and variables → Actions*):
   - `KEYSTORE_BASE64` – Keystore, base64-kodiert (`base64 -w0 release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

   Keystore erzeugen (falls noch keiner vorhanden):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias meditate -keyalg RSA -keysize 4096 -validity 36500
   ```
   Den Keystore gut aufbewahren – Updates funktionieren nur mit derselben Signatur.
2. Tag pushen:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
   Der Workflow baut die signierte APK und hängt sie an ein GitHub-Release.
3. In Obtainium: *App hinzufügen* → `https://github.com/haigerman/meditate`.

Versionscode wird aus dem Tag berechnet (`v1.2.3` → 10203).

## Lokal bauen

```bash
./gradlew :app:assembleDebug
```

`./gradlew :app:testDebugUnitTest` rendert außerdem jeden Klang als WAV nach
`app/build/sound-previews/` – praktisch zum Probehören ohne Handy.

## Quellen

- Wasser: „mountain stream" von **piermic** auf freesound.org (Sound #376192),
  Lizenz **CC0** (gemeinfrei); gefiltert und lautstärkeangepasst.
- Wald: Vogelgesang-Aufnahme aus der App *First Light* (`birdsong.mp3`); Anfang und
  ausblendendes Ende abgeschnitten, Hochpass 120 Hz, lautstärkeangepasst.

# Kids TV

An app for Google TV / Android TV that becomes the kids' home screen: rows of posters
with only the titles you approved (Disney+, Apple TV, Netflix, Prime Video). Each poster
opens the title straight in the official app. A **watchdog** brings the TV back to this
screen when the kids leave for the app's catalog, open another title or another app, or
press Home.

- **Kids mode:** off by default, so the TV works normally for the adults. Turn it on with
  "Turn on kids mode" before handing the TV over. Leaving it takes a PIN, typed the way
  Google TV asks for its own: ↑ ↓ pick a row of three digits, ← OK → type one of them,
  and only dots show on screen.
- **Rows:** "Continue watching" (the last 6 titles opened on that TV), then the rows from
  `titles.json` (Series, Movies, Documentaries…), then free apps (e.g. Jellyfin).
- The titles in `titles.json` are an **example**: one family's list.

## What you need

- A TV with **Google TV / Android TV** (tested on a TCL with Android TV 14). Any TV with a
  Google TV Streamer / Chromecast with Google TV works too.
- A computer with the **Android SDK** and **JDK 17+**. The easiest way is to install
  [Android Studio](https://developer.android.com/studio), which ships both, and point
  `JAVA_HOME` at its JDK (`<android-studio>/jbr`).
- `adb` on your PATH (part of the SDK, in `platform-tools`).

## Install

1. **Developer mode on the TV:** Settings → System → About → click "Android TV OS build"
   7 times.
2. **Wireless debugging:** Settings → System → Developer options → Wireless debugging → On.
   The TV must be on **Wi-Fi** (it doesn't work over an Ethernet cable).
3. **Pair once:** on the same screen, "Pair device with pairing code". On the computer:
   ```sh
   adb pair <ip>:<pairing-port> <code>
   ```
4. **PIN:** create `local.properties` at the root with `kidsTv.pin=1234` (your PIN).
   Without it, the PIN is `1234`.
5. **Install**, with the "IP address & port" shown on the Wireless debugging screen (that
   port changes whenever the TV restarts):
   ```sh
   ./install.sh <ip>:<port>
   ```
   The script builds, installs and switches the watchdog on. Android won't let you enable
   accessibility for an app installed outside the Play Store from Settings, so it does it
   over `adb`. On **TCL** TVs it also grants the `AUTO_START` permission; without it,
   TCL's system refuses to run the watchdog.

The app shows up in the TV's app list as **Kids TV**.

## Choosing the titles (`app/src/main/assets/titles.json`)

```json
{
  "rows": ["Series", "Movies", "Documentaries"],
  "items": [
    {
      "id": "bluey",
      "title": "Bluey",
      "kind": "series",
      "row": "Series",
      "app": "disney",
      "link": "https://www.disneyplus.com/browse/entity-fa6973b9-e7cf-49fb-81a2-d4908e4bf694",
      "poster": "https://images.justwatch.com/poster/185497966/s718/bluey.jpg",
      "names": ["Bluey"]
    }
  ],
  "free_apps": [{ "title": "Jellyfin", "package": "org.jellyfin.androidtv", "poster": "" }]
}
```

- `app`: `disney`, `apple`, `netflix` or `prime`.
- `link`: the title's address on the service's website (that's what opens in the TV app):
  - **Disney+:** `https://www.disneyplus.com/browse/entity-…`
  - **Apple TV:** `https://tv.apple.com/us/show/<name>/umc.cmc.…` (or `/movie/…`)
  - **Netflix:** `https://www.netflix.com/watch/<id>`. Use `/watch/`, not `/title/`: on
    Netflix the watchdog requires the video to be playing.
  - **Prime Video:** `https://watch.amazon.com/watch?asin=<ASIN>&time=0&territory=US`
- **Finding links:** [JustWatch](https://www.justwatch.com) shows where each title streams.
  Open the title's page on the service's website and copy the address. **Check it on the
  TV**: JustWatch's links are sometimes wrong (Bluey's opened "Bluey Compilations", and
  Frozen 2's and Moana's opened the Sing-Along versions).
- `names`: what the watchdog accepts as "on this title", i.e. the name as it appears on the
  app's page (English, on the US apps). A text matches when it is exactly a name or starts
  with one followed by a comma or colon; never "contains", so "Bluey Compilations" doesn't
  pass as Bluey.
- `free_apps`: apps opened with no watchdog, such as Jellyfin, whose server already limits
  what the kids see.

After editing, run `./install.sh <ip>:<port>` again.

## What the watchdog can see in each app

| App | What it uses |
|---|---|
| Disney+ | The now-playing title and the screen text. Detects the catalog by its menu. |
| Apple TV | The screen text (sometimes). Detects the sidebar. On text-less screens: whether it's playing, and the Back key pressed on the show page. |
| Netflix / Prime | Only whether it's playing, paused or stopped. Leaving the player sends the TV back to the kids' screen. |

**Known limitations:**
- On **Netflix and Prime**, a fast kid can switch titles within a few seconds. Pair this
  with the per-title restrictions of the Netflix kids profile.
- On **Apple TV**, opening a recommendation from inside a show's page isn't detected.
- When the TV **restarts**, the debugging port changes; reinstalling needs the new one.
- The PIN is baked into the APK: it keeps kids out, not determined adults.

## Ideas (built in the original project, left out of this version)

- **TV time:** every second, the watchdog already knows which title is in front and
  whether it's playing. In the original project it records one segment per title
  (counting only the seconds that play), keeps it in a file on the TV and sends it to its
  own server, which shows minutes per day and the most-watched titles. A spreadsheet or a
  simple database would work too.
- A **daily time limit** built on that log.
- **One profile per child** when turning kids mode on, each with its own rows.

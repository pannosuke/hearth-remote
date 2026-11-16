# Hearth remote parity

The Android phone app and the iPhone web remote are one remote. They show the same screens, and they use the same words.

`shared/i18n/en.json` and `shared/i18n/ja.json` are the catalog. They match `app/src/main/res/values/strings.xml` and `values-ja/strings.xml`. After editing either XML file, run `python3 scripts/export_i18n.py`. A unit test fails if the JSON drifts.

From v0.3.0 on, a feature is not done until it ships in both clients.

## Language

Settings has three choices: system default, English, and 日本語. The Android app applies the choice with `AppCompatDelegate.setApplicationLocales`, so it takes effect immediately and stays after the app restarts. The web remote uses the same three choices and reads the JSON catalog. Language names stay `English` and `日本語` in both languages. “System default” follows the phone.

## Screens

| Screen | What it does |
| --- | --- |
| Setup | TV address, pairing code, connect |
| 地デジ | Channel, programme, Explain news, Snap screen, volume, presets 18/22/33, mute, subtitles on/off, temperature pill |
| YouTube | Profile chip, Sportsnet highlights, transport keys, captions, volume |
| Jellyfin | Sign-in prompt, now playing, seek, subtitles, up next, volume |
| Remote | D-pad, OK, back, home, play/pause, volume, when no special screen is showing |
| Trackpad | Swipe pad, back, home, menu, play/pause, volume, channel |
| Apps | YouTube, Netflix, Disney+, Prime Video, Jellyfin, TVer, NHK, Hulu, 地デジ |
| Settings | Language, overlays, Jellyfin account, connection log, forget pairing, subtitle macros, version |
| Connection log | Recent protocol lines and the close reason |

Power sits on every paired screen. The version number sits in the corner.

## Features the web remote must keep

- Pairing and the saved TV address
- 地デジ controls, Osaka channel cards, and the guide
- Volume down on the left, volume up on the right, mute, and presets 18, 22, and 33
- Explain news on 地デジ. `POST http://<tv>:8765/api/explain`, and `GET` the same path if POST returns 405. The TV toggles the English summary, which stays up for 60 seconds. The phone shows requesting, sent, or an error. A 404 says to update Hearth on the TV.
- Snap screen, next to Explain news. The rear camera takes one photo of the TV. The photo is not saved on the phone: no gallery, no MediaStore, no file left behind. Upload is `POST http://<tv>:8765/api/explain/photo` as multipart field `photo`, or as a raw `image/jpeg` if the set rejects the form. The picture is scaled so the long edge is at most 1600 pixels. The TV shows the English summary. A 404 says to update Hearth on the TV.
- Subtitles on and subtitles off
- YouTube highlights and playback keys
- Jellyfin playback, subtitles, and up next
- App launches
- Hearth overlays: `wxpill`, `quake`, `warn`, `sabres`, `weather`
- Power
- English and Japanese, with the same string keys

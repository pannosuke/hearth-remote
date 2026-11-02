# hearth-remote: plan (draft for Eric's approval)

> **Don't start building until Eric has seen and approved this plan.** Send him a short summary (the recommendation in §3, the screens in §4 and voice in §6) and ask about the open questions in §10.
>
> Background you need first: [hearth-launcher/handoff](https://github.com/pannosuke/hearth-launcher/tree/main/handoff). The household and preferences are in CONTEXT.md; the TV quirks, channel map and Jellyfin API notes are in TECHNICAL.md.

## 1. Goal
A phone remote on Eric's **Samsung Galaxy S24 Ultra** (Android 14+/One UI) that **replaces Remote ATV** and is better for this household:
- It knows what's on the TV and adapts to it (contextual).
- It can jump straight to what they actually watch.
- It takes **voice commands in Japanese and English** through the phone's microphone.

## 2. Requirements (from Eric)
1. **Basic remote:**
   - A **trackpad** (swipe = D-pad, tap = OK) plus Back, Home and Menu.
   - **Volume** up/down/mute and **channel** up/down.
   - A **big play/pause** button.
   - Power.
2. **One-tap Jellyfin resume:** the current Continue Watching item, resumed straight into playback (Hearth's launcher already does this; see §5).
3. **Subtitle switching, JA/EN only:** a one-tap toggle between **Japanese**, **English** and **Off** for the playing Jellyfin item. Match subtitle tracks whose language is `jpn`/`ja`, or whose title/DisplayTitle contains "Japanese" or **日本語**; the same for `eng`/"English"/英語. Hide every other language; they're never used.
4. **YouTube NHL highlights:** play the day's **Sportsnet NHL highlights** from YouTube on the TV, on **Eric's profile**. His son's profile is used too, so allow choosing between the two. His son's *Disney+* profile is "Kenken"; his YouTube profile name is unknown, so ask Eric.
5. **Live TV screen:**
   - One card per channel (10, 8, 7, 6, plus 4/1/2/3), each with the station logo, the show on now, its end time and progress.
   - Tap a card to tune the TV.
   - **Quick switches** between TV stations, Jellyfin and Home.
6. **Contextual design:** the main screen changes with what the TV is showing (Live TV / Jellyfin / YouTube / Home / other app), with the most likely actions large and on top.
7. **Voice on the phone mic**, in Japanese and English, e.g.:
   - ブルーイ見せて (show Bluey)
   - 8チャンネルつけて (put on channel 8)
   - ゴジラの続き (continue Godzilla)
   - "resume Godzilla", "channel 10", "play Bluey"
   - Use fuzzy matching (see §6).
8. **Style and conventions:** the same look as Hearth (dark, polished, Apple TV-like, heavy amber glow on focus, station logos) and **snappy**. Japanese labels where they help his wife.

## 3. How the phone controls the TV: options and recommendation

| Option | What it is | Pros | Cons |
|---|---|---|---|
| **A. Android TV Remote protocol v2** | The protocol used by the Google TV phone app and by Remote ATV. TLS with a client certificate. **Pairing on port 6467**: the TV shows a 6-character code, which the user enters on the phone. **Commands on port 6466**: protobuf `RemoteMessage`s such as `remote_key_inject` (any Android keycode), `remote_adjust_volume_level`, IME text, and **`remote_app_link_launch_request`** (open any deep link or URL on the TV). | Works in **every app**, at low latency. No developer options or adb needed. Survives reboots. It's already working on this TV (Remote ATV uses it, and the TV's `Android TV Remote Service` is on the keep list). Reference implementation: [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) (Python; port its `remotemessage.proto`/`polo.proto` to Kotlin). | Can't read TV state (what's playing, the guide). No direct "tune to channel id"; that has to be number keys or a deep link. |
| **B. adb over the network** | The phone acts as an adb host (e.g. the `dadb` Kotlin library) and runs `input keyevent`, `am start …`. | Full power. Can tune exact channels, read `dumpsys`, anything. | Each `input` command spawns a process on the TV: roughly **hundreds of ms per key** on this weak chip, so a trackpad would feel laggy. It needs network debugging left on permanently, which is a security exposure. Every new phone key needs the TV's Allow prompt. |
| **C. Companion LAN service inside Hearth** | A tiny HTTP server in Hearth's always-running `WakeService` on the TV. | Hearth already has the **guide data** (the "裏番組:地上" row with now-playing titles and end times), the **exact tune URIs**, the foreground app (UsageStats) and Jellyfin access. One request can give the phone the whole Live screen and tune instantly via ACTION_VIEW. | Can't inject key presses into other apps (no INJECT_EVENTS permission), so it **can't do a trackpad or play/pause in Netflix**. Needs a pairing token for LAN security. Adds code to Hearth (snappiness must not regress). |
| (D. Jellyfin Sessions API) | The phone talks to the Jellyfin server directly. | Precise control of Jellyfin playback: resume, play/pause, seek, **subtitle and audio track selection**, now playing. Hearth already uses it. | Jellyfin only. The TV app honours remote Play only while it's in front. |

**Recommendation: a hybrid.**
- **A** for everything key-like: the trackpad, D-pad, Back/Home, volume, channel ±, power, play/pause in any app, and launching apps or deep links (Bluey `disneyplus://www.disneyplus.com/browse/entity-fa6973b9-e7cf-49fb-81a2-d4908e4bf694`, YouTube video URLs, Jellyfin).
- **C** for TV smarts: `GET /state` (foreground app, current channel), `GET /live` (channel cards with now-on, end time, progress, logo id), `POST /tune?ch=8`, `POST /open?target=home|jellyfin|bluey`.
- **D** for Jellyfin: one-tap resume (the same flow as Hearth's `JfPlay`), subtitles, play/pause/seek, the now-playing poster.
- **B is not used at runtime.** It stays only as the developer and installer path for Hearth.

**Fallback if Eric doesn't want Hearth changed:** A + D alone covers everything except guide data and exact tuning. Channels are then tuned with number keys (e.g. `KEYCODE_8`) and the Live screen shows no titles.

**Companion service details (if C is approved):**
- Port e.g. 8765, LAN only.
- Every request needs a **random token** generated by Hearth on first run and paired once: Hearth shows a 6-digit code or QR on the TV and the phone stores the token. Never commit the token, never log it.
- JSON over HTTP, served from WakeService's thread with no work while idle.
- Keep it small; Hearth has no dependencies, and `com.sun.net.httpserver` isn't available on Android, so write a minimal HTTP/1.1 handler on `java.net.ServerSocket`.
- Measure that Hearth's jank and memory numbers don't regress (see the launcher's TECHNICAL.md → Performance).

## 4. Screens (contextual)
The app polls `/state` every 2–3 s while it's in front (or uses a long-poll) plus the Jellyfin `GET /Sessions`, and picks the main screen:

- **Live TV in front:**
  - Large channel cards (10/8/7/6 on top, 4/1/2/3 smaller) with logo, title, "～14:55" and a progress bar. The current channel is highlighted. Tap to tune.
  - Ch ±, volume, a mini trackpad.
- **Jellyfin playing:**
  - Poster and title, a big **play/pause**, ±10 s / ±30 s skips.
  - **Subtitles [日本語] [English] [Off]**, with the current choice highlighted.
  - Volume.
- **Jellyfin browsing / Home (Hearth):**
  - Quick tiles: **▶ Resume <title>** (Continue Watching item 1), Bluey, NHL highlights, Live 10/8, Jellyfin.
  - The trackpad.
- **YouTube / Netflix / other apps:** the full trackpad, a big play/pause, volume, Back/Home.
- **Always visible:**
  - A bottom bar: **TV** (Live), **Jellyfin**, **Home**, and a big **mic** button.
  - Power at the top.
  - Haptic feedback on every key.

## 5. Jellyfin specifics
- **Server:** `http://192.168.10.114:8096`. Profile **Queen Bee**, user id `e7026df16b7f41769304ba76385dfde6`. Header `X-Emby-Token`. The **API key is entered on the phone at setup** and stored in Android Keystore-backed storage; it's never in the repo.
- **Resume:**
  1. `GET /Users/{uid}/Items/Resume?Limit=1&MediaTypes=Video`.
  2. Open the item on the TV: a v2 app link, or Hearth's `/open`.
  3. Find the TV session in `GET /Sessions`: Client **"Jellyfin Android TV"** (not "…for Android TV").
  4. `POST /Sessions/{id}/Playing?playCommand=PlayNow&itemIds=…&startPositionTicks=…`.
  5. Verify `NowPlayingItem`; retry ×3.
  - The TV app must be in front for remote Play.
- **Transport:** `POST /Sessions/{id}/Playing/PlayPause` (also `Seek?seekPositionTicks=`, `Stop`).
- **Subtitles:**
  - Read `NowPlayingItem` → `MediaStreams` (Type=Subtitle: Index, Language, DisplayTitle, Title).
  - Switch with `POST /Sessions/{id}/Command` `{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"<n>"}}` (`-1` = off).
  - **Verify on the TV that the Android TV app (0.19.x) honours it.** If not, fall back to re-issuing PlayNow at the current `PositionTicks` with `&subtitleStreamIndex=<n>` (and `mediaSourceId`).
- Eric's wife watches with Japanese subtitles, so consider remembering the last choice per series.

## 6. Voice (phone mic, JA + EN)
**Why the phone:** in research on 2026-11-02 (adb on the TV):
- The **TV has no working microphone**: only `tv_tuner` and `HDMI_IN` input devices are connected; BACK_MIC is declared but not wired.
- Its remote is **IR only**, with no bonded Bluetooth voice remote.
- The system recogniser (Google "katniss") is online-only; in testing on this TV it returned NO_SPEECH_DETECTED because there's no audio source. Android 10 also has no language-detection API.
- An on-TV option would have needed a USB mic and an offline model on a weak 32-bit chip. **sherpa-onnx SenseVoice-Small** (about 230 MB int8, auto-detects ja/en/zh/ko/yue) was the best candidate, but it's heavy for the TV's ~1 GB free.
- A wake word ("OK TV") was judged technically possible with openWakeWord (about 3–6% CPU and 50–80 MB, estimated), but the TV's own audio would cause false triggers without an echo-cancelling speakerphone. Porcupine supports Japanese but needs a vendor access key. Vosk small ja/en models were another option (the full 1 GB ja model is too big); whisper-tiny is too slow on arm32.
- **The phone avoids all of that.**

**Recognition on the S24:**
- Push-to-talk on the big mic button; release or silence ends it.
- **Primary:** Android `SpeechRecognizer` (Google's service, on-device where available: `createOnDeviceSpeechRecognizer`, API 33+) with:
  - `EXTRA_LANGUAGE = "ja-JP"`
  - on API 34+, `EXTRA_ENABLE_LANGUAGE_DETECTION = true` and `EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES = ["ja-JP","en-US"]` (and/or `EXTRA_ENABLE_LANGUAGE_SWITCH`)
  - `EXTRA_MAX_RESULTS = 5`, and match against all N-best results.
  - Language detection support depends on the implementation. **Test it on the S24.** Samsung's default recogniser may differ, so bind explicitly to Google's recognition service if it's needed.
  - If detection is unavailable, run in ja-JP: Japanese models usually write English titles as katakana (リジューム ゴジラ), which the matcher handles anyway.
- **Offline alternative:** sherpa-onnx SenseVoice-Small on the phone (arm64, fast on the S24), with automatic ja/en detection.
- Show what was heard ("ゴジラの続き → Resume: Godzilla Minus One") with a 1–2 s undo before acting, or at once if confidence is high.

**Intent grammar (bilingual, simple rules run before fuzzy matching):**
| Intent | Japanese patterns | English patterns |
|---|---|---|
| Channel | `(\d+|一|二|…|十|十二|じゅう…)\s*(チャンネル|ch|ちゃんねる)` + つけて/にして/見せて/回して; station names (読売/よみうり/ytv, 関西テレビ/カンテレ, テレビ大阪, ABC/朝日, MBS/毎日, NHK, Eテレ, サンテレビ) | "channel N", "put on/turn on channel N", station names |
| Resume | `…の続き`, `続きを見る`, `つづき` | "resume …", "continue …", "keep watching …" |
| Play | `…見せて`, `…再生`, `…かけて`, `…つけて` | "play …", "put on …", "watch …" |
| Subtitles | `字幕 日本語/英語/なし/消して` | "Japanese/English subtitles", "subtitles off" |
| Transport/volume | 止めて/一時停止/再生, 音 大きく/小さく, ミュート | pause/play/stop, volume up/down, mute |
| Navigate | ホーム, テレビ, ジェリーフィン | home, TV, Jellyfin |
| NHL | NHL/ハイライト/ホッケー | "NHL highlights", "hockey" |

Channel numbers map to remote keys 10/8/7/6/4/1/2/3 (ytv=10, KTV=8; see the channel map in the launcher's TECHNICAL.md). Kanji and kana numbers are normalised first: 十 = 10, じゅう = 10, はち = 8.

**Fuzzy title matching (the important part):**
1. **Candidates:**
   - For "resume", Jellyfin **Continue Watching** first, then Next Up.
   - For "play", the whole library: `GET /Users/{uid}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=OriginalTitle,SortName`. That's about 90 titles, cached on the phone.
   - **Bluey** and other Disney+ deep links.
   - Station names.
2. **Normalisation of both sides:**
   - NFKC, lowercase, strip punctuation, articles and common suffixes (`の続き`, `見せて`, "the").
   - Convert **katakana → hiragana → romaji** with ICU (`android.icu.text.Transliterator`, "Katakana-Hiragana; Hiragana-Latin; Latin-ASCII"), e.g. ブルーイ → "buruui".
   - Handle the long-vowel mark ー by doubling the vowel, then collapse vowel runs.
3. **Sound-alike rules** applied to the romaji and to English titles before comparing:
   - l→r, v→b, th→s/z, z↔j, si→shi, ti→chi, tu→tsu, f→h, wi→ui.
   - Drop epenthetic vowels after consonants (buruui → brui ≈ bluey → bruei → "brui").
   - Double consonants become single.
4. **Match against Name, OriginalTitle and SortName.** 21 of ~90 Jellyfin items already have a Japanese `OriginalTitle` (Godzilla Minus One = ゴジラ-1.0); the other 69 are English only.
5. **Aliases:** a small editable alias list on the phone, e.g. ブルーイ→Bluey (Disney+), ゴジラ→Godzilla Minus One, テッド・ラッソ→Ted Lasso. Allow adding an alias from a failed match ("Did you mean…?").
6. **Scoring:** Jaro-Winkler or normalised Levenshtein on the normalised strings, plus a token-overlap bonus and a boost for Continue Watching items. Act above about 0.85. Between about 0.6 and 0.85, show the top 3 to tap. Below that, show "didn't catch that" with the transcript.
7. **Test set:** before shipping, build a unit test from the examples above plus every title in the library (spoken JA/EN variants).

## 7. YouTube NHL highlights
- **Finding the video:** use Sportsnet's YouTube channel RSS feed (`https://www.youtube.com/feeds/videos.xml?channel_id=<Sportsnet channel id>`). It needs no API key. Look up the channel id once, and pick the newest entries whose title matches the highlight pattern (team names / "Highlights"). Show a list of today's games (newest first), and tap one to play.
- **Playing it on the TV:**
  - Option 1: a v2 `remote_app_link_launch_request` with `https://www.youtube.com/watch?v=<id>` (opens the YouTube TV app, `com.google.android.youtube.tv`). It plays on **whatever profile the TV app currently has selected**; the profile can't be chosen by intent.
  - Option 2: **cast from the phone's YouTube app** (`mediashell`/Cast is on the TV), so playback runs on the phone's signed-in account (Eric's).
  - Verify which gives "Eric's profile" vs "son's profile" reliably and ask Eric (§10).

## 8. Tech stack
- Kotlin, Jetpack Compose, Gradle (fine on the phone; the TV launcher stays Gradle-free). `minSdk 34` (S24 only), arm64.
- Store the v2 client certificate and private key in Android Keystore, after a one-time pairing (the TV shows the code).
- Store the Jellyfin key and the Hearth token in Keystore-backed EncryptedSharedPreferences, entered at setup.
- Discover the TV at 192.168.10.105 (it's configurable; mDNS `_androidtvremote2._tcp` can find it).
- Install by sideloading a signed release APK to the phone, delivered by Eric's choice of route. No Play Store.

## 9. Phases (each ends with Eric's review)
1. **Plan approval** (this doc), then the answers to §10.
2. **Basic remote (A):** pairing, trackpad, keys, volume, channel ±, play/pause, power. Eric then retires Remote ATV.
3. **Jellyfin (D):** resume, subtitles JA/EN/Off, transport, the now-playing screen.
4. **Hearth companion (C):** `/state`, `/live`, `/tune`, `/open`, plus the contextual screens and the Live screen. This involves a Hearth release (the launcher repo), tested for jank.
5. **Voice:** recogniser and matcher, with the test set.
6. **NHL highlights.**

## 10. Open questions for Eric
1. OK to add a small LAN service to Hearth (option C), or keep Hearth untouched (A + D only, so no show titles on the phone's channel screen)?
2. YouTube: which profile is the default, and what is the son's YouTube profile called? Is casting from the phone acceptable?
3. Which channels go on the phone's Live screen: just 10/8/7/6, or all 8?
4. Voice: act immediately, or always confirm first? Is the Google recogniser (may use the cloud) OK, or should it be offline only?
5. How should the APK get onto the phone (adb sideload from a computer, or a download link)?
6. Should his wife's phone get it too?

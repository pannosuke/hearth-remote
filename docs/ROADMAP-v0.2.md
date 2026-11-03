# Hearth Remote v0.2 roadmap

Plan only. v0.1.7 does not build any of this. It only makes app shortcuts launch, and it asks the TV for the IME feature so a later version can see which app is in front.

The idea Eric asked for: the phone remote changes by itself when the TV's foreground app changes. The remote protocol reports that app on `RemoteImeKeyInject` (`remote_ime_key_inject`, field 20) in `app_info.app_package` (field 12). androidtvremote2 reads the same field and calls it `current_app`. Examples: `com.google.android.youtube.tv`, `org.jellyfin.androidtv`, `com.google.android.tv`.

v0.1.7 negotiates IME (`FEATURE_IME`) in the configure handshake. Until something listens for `RemoteImeKeyInject`, the phone still shows one remote for every app.

## How each feature can actually work

| Feature | Mechanism | Feasible in v0.2? |
| --- | --- | --- |
| Switch the on-screen remote when the TV app changes | Remote protocol `RemoteImeKeyInject.app_info.app_package` | Yes. Needs the IME bit, which v0.1.7 requests. The TV sends this when focus changes, not on a poll. |
| Terrestrial: volume up/down, mute | Remote keys `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN`, `KEYCODE_VOLUME_MUTE` | Yes. These already exist on the D-pad screen. |
| Terrestrial: channel up/down | `KEYCODE_CHANNEL_UP`, `KEYCODE_CHANNEL_DOWN` | Yes, while the tuner is focused. |
| Terrestrial: channel cards (10, 8, 7, 6, …) | Digit keys `KEYCODE_0`…`KEYCODE_9`, sent as a short sequence | Yes for the number. The card is just a button that sends those keys. |
| Terrestrial: what's on now | Nothing in the remote protocol | No, not from the TV protocol. Needs a separate Japan EPG source. Do not block the remote on this. |
| Terrestrial: one-tap subtitles | Try `KEYCODE_CAPTIONS` first | Maybe. Many tuners ignore it. Fallback is adb (`input keyevent 175` or a settings command), which is the same key with a worse setup. |
| YouTube: play/pause | `KEYCODE_MEDIA_PLAY_PAUSE` while YouTube is focused | Yes. |
| YouTube: skip / seek jumps | `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_MEDIA_REWIND`, `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`. A 10-second jump is often left/right on the D-pad inside the player, which is not a labeled seek API. | Keys yes. Exact "jump 10 seconds" is not a protocol message. |
| YouTube: subtitles on/off | `KEYCODE_CAPTIONS`, or a D-pad sequence through YouTube's menu | Unreliable. There is no caption-toggle message. |
| YouTube: NHL highlight cards from Sportsnet | YouTube Data API (API key) to list recent videos on the Sportsnet channel, then `RemoteAppLinkLaunchRequest` with `https://www.youtube.com/watch?v=VIDEO_ID` | Yes, if Eric supplies an API key. The remote protocol cannot search YouTube. |
| YouTube: profile switcher | None | Not feasible. See below. |
| Jellyfin: play/pause | Jellyfin server API `Sessions/{id}/Playing/{PlayPause,Pause,Unpause}` | Yes, once the phone has the server URL and an API token. |
| Jellyfin: volume | TV volume keys, not the Jellyfin session volume | Yes via remote keys. Jellyfin's own volume command only changes the player gain and is the wrong control for a TV. |
| Jellyfin: one-tap Japanese, then English, subtitles | `Sessions/{id}/Playing/SetSubtitleStreamIndex`. Read `NowPlayingItem.MediaStreams`, prefer `Type=Subtitle` and `Language=ja`, then `en`. | Yes. This is the right tool. Japanese is the default choice. |
| Jellyfin: Up next / next-episode cards | `Shows/NextUp` or the next episode from the current series, then `Sessions/{id}/Playing` with that item id | Yes. |
| Motion smoothing (AquoMotion) | Not a remote key and not a public Android setting | Unlikely. Only plausible path is adb, and Sharp may not expose the picture engine there. |
| Polished visual design | Phone UI only | Yes. No TV API. |

## What is not feasible

**YouTube profile switching has no API.** The YouTube Data API can list videos for a channel the API key can see. It cannot switch the account signed in on the TV. `KEYCODE_PROFILE_SWITCH` is an Android system key. It does not change the YouTube TV profile. A D-pad macro through YouTube's profile screen would break whenever YouTube moves a button. Do not build a profile switcher.

**"What's on now" is not in the remote protocol.** The TV does not send the current program title. A card that shows the show name needs an EPG the phone fetches itself. That is a separate project from the remote.

**AquoMotion is not a remote command.** Android TV Remote Protocol v2 has no picture-setting message. Stock `settings` keys do not control Sharp's motion engine. adb-over-network might find a vendor command, and it might not. Treat a one-tap motion toggle as an experiment after the remotes that have a real API.

**YouTube subtitles are not an API either.** The Data API does not toggle the player's captions. `KEYCODE_CAPTIONS` is worth one attempt. A scripted menu walk is not worth maintaining.

## Mechanisms, in one place

- **Remote-protocol keys.** Volume, mute, channel, digits, play/pause, seek-ish media keys, captions key. Work only while that app is focused. No text comes back except volume level and the foreground package.
- **App links.** `RemoteAppLinkLaunchRequest` field 90. `https://www.youtube.com` and `https://www.youtube.com/watch?v=…` open YouTube. Other apps use `market://launch?id=<package>`. This launches an app. It does not press buttons inside it.
- **Jellyfin API.** Sessions, Playstate, `SetSubtitleStreamIndex`, Next Up. Needs the server address and an access token stored on the phone. This is how Japanese and English subtitles and the next episode actually work.
- **YouTube Data API.** Needs an API key. Used only to fill Sportsnet highlight cards. Playback is still an app link.
- **adb over the network.** Last resort for TV settings the protocol does not have: system captions if `KEYCODE_CAPTIONS` does nothing, and AquoMotion if a command even exists. Requires wireless debugging on the TV and is easy to break. Do not use it for Jellyfin subtitles or YouTube playback.

## Recommended build order

1. **Foreground app.** On `RemoteImeKeyInject`, store `app_package` and switch the visible remote. Empty or launcher package shows the generic remote. This is the shell everything else hangs on.
2. **Terrestrial layout.** Volume, mute, channel up/down, and a row of channel cards that send digit keys. Subtitles as one `KEYCODE_CAPTIONS` button, with a note if the tuner ignores it. Leave "what's on now" out until there is an EPG source.
3. **YouTube layout.** Play/pause and media seek keys. Sportsnet cards: Data API search or playlist for that channel, each card sends a watch URL. No profile switcher.
4. **Jellyfin layout.** Settings for server URL and token. Play/pause through Playstate. One button that calls `SetSubtitleStreamIndex` for Japanese, and a second for English. Up next cards from Next Up that start that episode.
5. **adb experiments.** Only after the three remotes work. First confirm whether `KEYCODE_CAPTIONS` is enough. Then see if any adb command toggles AquoMotion. Drop it if the setting is not there.
6. **Visual pass.** Do this after the layouts exist, so the polish is applied to the real screens: one type scale, large hit targets, the foreground app named at the top, cards that show a title and a single action. The v0.1 dark theme stays the base.

Package names the v0.1.7 shortcuts already use, for the switcher to match against:

| App | Package or link |
| --- | --- |
| YouTube | `https://www.youtube.com` (foreground package is usually `com.google.android.youtube.tv`) |
| Netflix | `com.netflix.ninja` |
| Disney+ | `com.disney.disneyplus` |
| Prime Video | `com.amazon.amazonvideo.livingroom` |
| Jellyfin | `org.jellyfin.androidtv` |
| TVer | `jp.co.tver.tvapp` |
| NHK | `jp.nhk.plus.tv` (Android TV app; the phone NHK ONE app is `nhk.app.tep`) |
| Hulu (Japan) | `jp.happyon.android` |
| Live TV | `com.google.android.tv` (Live Channels; a Sharp tuner app may differ) |

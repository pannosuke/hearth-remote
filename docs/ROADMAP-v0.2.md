# Hearth Remote v0.2 roadmap

## Sharp IP control

The TV has Sharp IP control on TCP 10002, at the same address as the Android TV remote. There is no login and no greeting, so a command can be sent as soon as the socket connects. The TV allows one session at a time and closes it after 60 seconds idle. That idle teardown was aborting the Android TV remote session on port 6466, so the phone closes its own socket with a FIN after 15 seconds instead of waiting for the set. A power query still runs about every 20 seconds and then the socket is allowed to close. Eric is the sole user.

A command is exactly 8 ASCII characters plus CR: a 4-character command and a 4-character parameter padded with spaces. The reply is `OK`, `ERR`, or a value.

Verified live on this set:

| Command | Reply | Meaning |
| --- | --- | --- |
| `POWR????` | `1` | power state |
| `VOLM????` | `20` | volume, 0–100 |
| `MUTE????` | `2` | 2 = off, 1 = on, 0 toggles |
| `CHRC????` | `TB10` | terrestrial, one-touch button 10 |

`IRCO` plus 4 hex digits sends a remote key: subtitle `0267`, enter `0152`, up `0157`, down `0120`, volume up `0114`, volume down `0115`, mute `0117`, channel up `0111`, channel down `0112`, digits 1–9 `024E`–`0256`, 0 `0257`, back `01E4`, home `02BB`, guide `0260`, display `011B`. `VOLM` sets the level. `CHUP` / `CHDW` step the channel. `CHRC` plus 1–12 is the one-touch button.

Power uses `POWR`. `POWR????` returns `1` when the set is on and `0` in standby. `POWR0` puts it in standby. `POWR1` wakes it. `POWR1` from standby does nothing until power-on over IP is enabled. The phone sends `RSPW2` once while the set is on (command `RSPW`, parameter `2`, which allows power-on over IP from any address) and remembers that the set accepted it. The top-bar power button sends Android TV `KEYCODE_POWER` while the 6466 session is up, and uses `POWR0` / `POWR1` when that session is down.

v0.2.0 phase 1 is the foreground shell, the terrestrial screen, and the generic remote. YouTube, Jellyfin, and AquoMotion are still later. v0.2.2 fills "What's on now" from the public J:COM XMLTV (dbghelp/JCOM-TV-EPG `jcom.xml`, no API key). The phone caches the eight Osaka terrestrial channels and refreshes every five minutes.

The phone switches remotes from `RemoteImeKeyInject` (`remote_ime_key_inject`, field 20) `app_info.app_package` (field 12). Live TV is `jp.co.sharp.av.android.aquostvapp`. If the TV has not reported a package, a `CHRC` value that starts with `TB` selects the terrestrial screen. A known app, such as `com.google.android.youtube.tv`, stays on the generic remote even when `CHRC` is still `TB10`, because that query can be the last tuner channel.

## How each feature can actually work

| Feature | Mechanism | Feasible in v0.2? |
| --- | --- | --- |
| Switch the on-screen remote when the TV app changes | Remote protocol `RemoteImeKeyInject.app_info.app_package` | Yes. Needs the IME bit, which v0.1.7 requests. The TV sends this when focus changes, not on a poll. |
| Terrestrial: volume up/down, mute | Sharp IP `VOLM` 0–100 and `MUTE` (0 toggle, 1 on, 2 off). The level and mute state are read back. | Yes. This is what v0.2.0 does. |
| Terrestrial: channel up/down | Sharp IP `CHUP` / `CHDW`, then `CHRC????` for the current channel | Yes. v0.2.0. |
| Terrestrial: channel cards (10, 8, 7, 6, then 1, 2, 4, 12, …) | Sharp IP `CHRC` plus the one-touch button number, not a digit-key sequence | Yes. v0.2.0. Osaka names: YTV, KTV, TVO, ABC, NHK, NHK E, MBS, 12, SUN. |
| Terrestrial: what's on now | Phone fetches dbghelp/JCOM-TV-EPG `jcom.xml` (XMLTV, no key) and shows the current title, time range, progress, and the next programme | Yes. v0.2.2. Button 12 has no listing. |
| Terrestrial: subtitles on/off | Sharp IP macro. Subtitle `0267` opens the drawer on Display Style, Enter `0152` opens the options, Down `0120` guesses On Screen, Up `0157` guesses Off, Enter confirms, Back `01E4` closes. Both sequences are editable in Settings. | Yes. v0.2.8. There is no subtitle-state query. The down/up count is a guess until Eric tries it. |
| YouTube: play/pause | `KEYCODE_MEDIA_PLAY_PAUSE` while YouTube is focused | Yes. |
| YouTube: skip / seek jumps | `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_MEDIA_REWIND`, `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`. A 10-second jump is often left/right on the D-pad inside the player, which is not a labeled seek API. | Keys yes. Exact "jump 10 seconds" is not a protocol message. |
| YouTube: subtitles on/off | `KEYCODE_CAPTIONS`, or a D-pad sequence through YouTube's menu | Unreliable. There is no caption-toggle message. |
| YouTube: NHL highlight cards from Sportsnet | YouTube Data API (API key) to list recent videos on the Sportsnet channel, then `RemoteAppLinkLaunchRequest` with `https://www.youtube.com/watch?v=VIDEO_ID` | Yes, if Eric supplies an API key. The remote protocol cannot search YouTube. |
| YouTube: profile switcher | None | Not feasible. See below. |
| Jellyfin: play/pause | Jellyfin server API `Sessions/{id}/Playing/PlayPause` | Yes. v0.2.3. |
| Jellyfin: volume | Sharp IP volume, not the Jellyfin session volume | Yes. v0.2.3 uses the same Sharp IP volume row as terrestrial. |
| Jellyfin: one-tap Japanese, then English, subtitles | `Sessions/{id}/Command` `SetSubtitleStreamIndex`. Read `NowPlayingItem.MediaStreams`, prefer `Type=Subtitle` and `Language=jpn`/`ja`, then `eng`/`en`. Index `-1` is off. | Yes. v0.2.3. A missing language is greyed out. |
| Jellyfin: Up next / next-episode cards | `Shows/NextUp`, then `Sessions/{id}/Playing?playCommand=PlayNow` | Yes. v0.2.3. |
| Motion smoothing (AquoMotion) | Not a remote key and not a public Android setting | Unlikely. Only plausible path is adb, and Sharp may not expose the picture engine there. |
| Polished visual design | Phone UI only | Yes. No TV API. |

## What is not feasible

**YouTube profile switching has no API.** The YouTube Data API can list videos for a channel the API key can see. It cannot switch the account signed in on the TV. `KEYCODE_PROFILE_SWITCH` is an Android system key. It does not change the YouTube TV profile. A D-pad macro through YouTube's profile screen would break whenever YouTube moves a button. Do not build a profile switcher.

**The TV does not send the current programme.** v0.2.2 reads the public dbghelp/JCOM-TV-EPG `jcom.xml` guide instead. If that feed goes stale, the alternative is a Hearth-on-TV companion that reads the set's own `TvContract` EPG and serves it on the LAN. Do not invent listings.

**AquoMotion is not a remote command.** Android TV Remote Protocol v2 has no picture-setting message. Stock `settings` keys do not control Sharp's motion engine. adb-over-network might find a vendor command, and it might not. Treat a one-tap motion toggle as an experiment after the remotes that have a real API.

**YouTube subtitles are not an API either.** The Data API does not toggle the player's captions. `KEYCODE_CAPTIONS` is worth one attempt. A scripted menu walk is not worth maintaining.

## Mechanisms, in one place

- **Sharp IP control, TCP 10002.** Volume, mute, channel, power, one-touch buttons, and the subtitle key for the tuner. Replies include the volume, mute state, current channel, and power. One socket, reused for 15 seconds, then closed before the TV's 60-second teardown. `RSPW2` is sent once while the set is on so `POWR1` can wake it from standby. This is the terrestrial remote.
- **Remote-protocol keys.** D-pad, OK, back, home, play/pause, and app links. The foreground package arrives on `RemoteImeKeyInject`. Volume level from this protocol is separate from Sharp `VOLM`.
- **App links.** `RemoteAppLinkLaunchRequest` field 90 is one URI. The TV parses it with `Intent.parseUri` (`URI_INTENT_SCHEME`). https links that an app's VIEW filter handles are sent as-is: YouTube `https://www.youtube.com`, Netflix `https://www.netflix.com/title`, Disney+ `https://www.disneyplus.com`, Prime Video `https://app.primevideo.com`, TVer `https://tver.jp`. Jellyfin, NHK+, Hulu Japan, and the Sharp tuner have no such link. Those go through the Hearth launcher (`net.pcg.hearth` 4.6): `https://hearth.local/launch?pkg=<package>` first, then `hearth://launch?pkg=<package>` if the TV answers `remote_error`. This set rejects `market://` and `intent://` and then FINs the socket. The phone does not close on `remote_error`. The following EOF reconnects immediately, without raising a visible error, and sends the `hearth://` retry on the new session. Sharp IP control has no app-launch command, and adb is not used. An app link launches an app. It does not press buttons inside it.
- **Jellyfin API.** Sessions, Playstate, `SetSubtitleStreamIndex`, Next Up. Sign-in stays the v0.2.3 request: `POST /Users/AuthenticateByName` with `X-Emby-Authorization: MediaBrowser Client="Hearth Remote", Device="Hearth Remote", DeviceId="…", Version="…"` and JSON `Username` and `Pw`. Later calls also send `X-Emby-Token`. Each request is written to the connection log with the path, status, and error text, never the password or token. The access token stays in encrypted prefs on the phone. The password is not stored.
- **YouTube Data API.** Needs an API key. Used only to fill Sportsnet highlight cards. Playback is still an app link.
- **adb over the network.** Last resort for TV settings the protocol does not have: system captions if `KEYCODE_CAPTIONS` does nothing, and AquoMotion if a command even exists. Requires wireless debugging on the TV and is easy to break. Do not use it for Jellyfin subtitles or YouTube playback.

## Recommended build order

1. **Foreground app.** Done in v0.2.0. On `RemoteImeKeyInject`, store `app_package` and switch the visible remote. The Sharp tuner package shows the terrestrial screen. A missing package with a `TB` channel is the fallback. Any other package shows the generic remote.
2. **Terrestrial layout.** Done in v0.2.0. Volume with a `VOLM` readout, mute state, channel up/down, and Osaka one-touch cards via `CHRC`. "What's on now" is done in v0.2.2 from the J:COM XMLTV. Subtitles on and off are separate Sharp IP macros as of v0.2.8.
3. **YouTube layout.** Play/pause and media seek keys. Sportsnet cards: Data API search or playlist for that channel, each card sends a watch URL. No profile switcher.
4. **Jellyfin layout.** Done in v0.2.3. The screen appears when the foreground package is `org.jellyfin.androidtv`. Settings has the server URL and a username/password sign-in. Play/pause, seek, Japanese/English/Off subtitles, the now-playing header, and Up Next cards. YouTube (item 3) is still open.
5. **adb experiments.** Only after the three remotes work. First confirm whether `KEYCODE_CAPTIONS` is enough. Then see if any adb command toggles AquoMotion. Drop it if the setting is not there.
6. **Visual pass.** Do this after the layouts exist, so the polish is applied to the real screens: one type scale, large hit targets, the foreground app named at the top, cards that show a title and a single action. The v0.1 dark theme stays the base.

Package names the v0.1.7 shortcuts already use, for the switcher to match against:

| App | Link |
| --- | --- |
| YouTube | `https://www.youtube.com` (foreground package is usually `com.google.android.youtube.tv`) |
| Netflix | `https://www.netflix.com/title` (`com.netflix.ninja`) |
| Disney+ | `https://www.disneyplus.com` (`com.disney.disneyplus`) |
| Prime Video | `https://app.primevideo.com` (`com.amazon.amazonvideo.livingroom`) |
| Jellyfin | `https://hearth.local/launch?pkg=org.jellyfin.androidtv`, then `hearth://launch?pkg=org.jellyfin.androidtv` |
| TVer | `https://tver.jp` (`jp.co.tver.tvapp`) |
| NHK | `https://hearth.local/launch?pkg=jp.nhk.plus.tv`, then `hearth://` (the phone NHK ONE app is `nhk.app.tep`) |
| Hulu (Japan) | `https://hearth.local/launch?pkg=jp.happyon.android`, then `hearth://` |
| Live TV | `https://hearth.local/launch?pkg=jp.co.sharp.av.android.aquostvapp`, then `hearth://` (the tuner this set actually runs; `CHRC` prefix `TB` is the fallback when the package is missing) |

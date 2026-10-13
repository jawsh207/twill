# Twill

Twill is an SMS/MMS app for Android, built on the new [GrapheneOS Messaging](https://github.com/GrapheneOS/Messaging) app (the Jetpack Compose rewrite, v13+).

A twill is a weave of many threads. The name reflects the idea: one inbox that brings several messaging protocols together. Today Twill does SMS/MMS and private Nostr messages.

## Features

Everything from GrapheneOS Messaging, plus the features below. Settings are grouped by protocol: today everything lives under **Settings › SMS Settings**, with app-wide items such as open source licenses on the main Settings page.


| Feature | Where to find it | Default |
| --- | --- | --- |
| **Delay sending.** Holds a message for 3 seconds after you tap send. A ring counts down around the send button, which turns into an X; tap it to cancel. Leaving the chat during the wait still sends it. | Settings › SMS Settings › Advanced, first in the Advanced section | Off |
| **Scheduled messages.** Pick a date and time for the message in the composer. When a chat has pending scheduled messages, a clock with a count appears next to the call button; tap it to see and cancel them. Text only. | Long-press the send button (on dual-SIM phones, pick Schedule message from the menu) | — |
| **Keep unread at top.** Unread chats, including ones marked as unread, sort above read ones. Pinned chats stay first. | Settings › SMS Settings | Off |
| **Website link previews.** Shows a title, description and image under links. | Settings › SMS Settings › Privacy, next to YouTube link previews | Off |
| **Send long messages as MMS.** Text that needs more than one SMS is sent as a single MMS instead of several parts. | Settings › SMS Settings › Advanced, last in the MMS section | Off |

## Nostr private messages

Twill can send and receive end-to-end encrypted Nostr direct messages (NIP-17: NIP-44 encryption inside NIP-59 gift wraps), using [Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz), the protocol library behind Amethyst.

- **Off until you sign in.** Nothing contacts a relay until you sign in under **Settings › Nostr Settings**.
- **Sign in with Amber** (recommended): your key stays in the Amber signer app (NIP-55). Approve Twill's requests once in Amber and messages decrypt in the background.
- **Or use a private key:** paste an nsec or create a new one. It is encrypted with the phone's Android Keystore and never leaves the device.
- **Conversation list:** when signed in, the list has collapsible **SMS** and **Nostr** sections. Use the **+** in the Nostr header to start a chat or a group, by NIP-05 address (`name@domain`), npub or public key. Long-press a Nostr chat to pin, mark unread or delete it.
- **Group chats (NIP-17):** name a group when you create it, or rename it from the chat header; members see the new name with your next message. Group messages show who sent them.
- **Attachments:** photos and files are encrypted on your phone (AES-GCM, NIP-17 kind 15) and uploaded to a Blossom media server, which only ever sees ciphertext. Photos are re-saved first so location and camera details never leave your phone. The server is set in Nostr Settings › Media (default `blossom.primal.net`); limit 25 MB.
- **Incoming attachments** are tap-to-download unless **Download attachments automatically** is on (off by default, because downloading reveals your IP address to the sender's file server). Downloads are hash-checked before decrypting.
- **NIP-05:** verified addresses show with a check mark in the chat header and member list.
- **Profile pictures:** off by default (loading them reveals your IP address to whatever server hosts each picture). Turn on in Nostr Settings › Media.
- **Message delivery:** only while Twill is open (default), check every 15 minutes, or always connected (permanent notification, instant messages).
- **Notifications:** Twill asks for notification permission when you sign in (it isn't granted automatically unless Twill is your default SMS app). If it's off, Nostr Settings shows a row to turn it on. Notes to yourself sent from another client notify too.
- **Your profile:** Nostr Settings shows your name, picture and NIP-05 as relays have them; tap refresh after changing your profile elsewhere.
- **Relays:** Twill publishes your DM relay list (kind 10050) and sends to each contact's DM relays. Relays can't read messages but can see your IP address.


### About website link previews

Previews are off by default because turning them on means Twill contacts the linked website when a message with a link is shown, and that website sees your IP address. Pages are fetched over HTTPS only (plain `http` links are upgraded), page and image sizes are capped, and nothing identifying is sent beyond a generic user agent.

## Installing

Twill uses its own package ID (`app.twill`), so every build installs **alongside** the system Messaging app rather than replacing it.

1. Open the [Actions tab](../../actions), pick the latest successful **Build installable APK** run, and download the `twill-apk` artifact.
2. Unzip it and install the APK on your phone. Later builds install as updates over it.
3. Open Twill and set it as your default SMS app when asked.

Each run produces two APKs:

| Artifact | Package | Name on phone | Use it for |
| --- | --- | --- | --- |
| `twill-apk` | `app.twill.perf` | Twill | Everyday use. Fully optimized, same as a release build. |
| `twill-debug-apk` | `app.twill.debug` | Twill d | Debugging only. Unoptimized, so scrolling stutters. |

Both are signed with a shared test key (`.github/ci/debug.keystore`) so new builds update old ones. That key is public, so it is only for test builds; real releases must be signed with a private key.

## Building

Requires JDK 17 and the Android SDK (Android Studio is the easiest way to get both).

```sh
git clone --recurse-submodules https://github.com/jawsh207/twill
cd twill
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Every push also builds an APK on GitHub Actions, and you can start a build by hand from the Actions tab with **Run workflow**.

Release builds need a signing key. See `keystoreProperties` in `app/build.gradle.kts`.

## Staying in sync with GrapheneOS

Twill tracks GrapheneOS Messaging as its upstream, so their fixes and new features can be merged in:

```sh
git remote add upstream https://github.com/GrapheneOS/Messaging.git
git fetch upstream
git merge upstream/main
```

To keep these merges easy, Twill's changes are kept small and mostly live in their own files.

## Roadmap

- Support for messaging protocols beyond SMS/MMS, woven into one inbox.

## Credits and license

Twill is a fork of [GrapheneOS Messaging](https://github.com/GrapheneOS/Messaging), which is itself based on the Android Open Source Project's Messaging app. Files that came from AOSP carry the Apache License 2.0 header and remain under that license. All credit for the base app goes to the GrapheneOS and AOSP contributors.

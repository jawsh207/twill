# Twill

Twill is an SMS/MMS app for Android, built on the new [GrapheneOS Messaging](https://github.com/GrapheneOS/Messaging) app (the Jetpack Compose rewrite, v13+).

A twill is a weave of many threads. The name reflects where the app is headed: one inbox that brings several messaging protocols together. Today it does SMS and MMS, with extra options added on top of GrapheneOS Messaging.

## Features

Everything from GrapheneOS Messaging, plus:

| Feature | Where to find it | Default |
| --- | --- | --- |
| **Delay sending.** Holds a message for 3 seconds after you tap send, with an Undo button. Leaving the chat during the wait still sends it. | Settings | Off |
| **Scheduled messages.** Pick a date and time for the message in the composer. Pending messages for the chat are listed in the same dialog and can be cancelled. Text only. | Conversation › ⋮ menu › Schedule message | — |
| **Keep unread at top.** Unread chats, including ones marked as unread, sort above read ones. Pinned chats stay first. | Settings | Off |
| **Website link previews.** Shows a title, description and image under links. | Settings › Privacy, next to YouTube link previews | Off |
| **Send long messages as MMS.** Text that needs more than one SMS is sent as a single MMS instead of several parts. | Settings | Off |

### About website link previews

Previews are off by default because turning them on means Twill contacts the linked website when a message with a link is shown, and that website sees your IP address. Pages are fetched over HTTPS only (plain `http` links are upgraded), page and image sizes are capped, and nothing identifying is sent beyond a generic user agent.

## Installing

Twill uses its own package ID (`app.twill`), so it installs **alongside** the system Messaging app rather than replacing it. Debug builds use `app.twill.debug` and show up as "Twill d".

1. Open the [Actions tab](../../actions), pick the latest successful **Build installable APK** run, and download the `messaging-debug-apk` artifact.
2. Unzip it and install the APK on your phone.
3. Open Twill and set it as your default SMS app when asked.

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

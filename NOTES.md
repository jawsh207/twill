# Twill: project notes

Twill is an SMS/MMS app forked from GrapheneOS Messaging (Compose rewrite, v13+). The name is a weave; the long-term plan is to add other messaging protocols alongside SMS/MMS.

## Rules

- **Every build type must install alongside the system Messaging app, never replace it.** `applicationId` is `app.twill` (debug and perf: `app.twill.debug`). Never set it back to `com.android.messaging`, and never add anything that only works under that package name.
- The Kotlin/Java source package and Gradle `namespace` stay `com.android.messaging`. Renaming them would make every upstream merge conflict.
- The app's display name is "Twill" (`app_name` in `res/values/strings.xml`); debug builds are "Twill d" (set in `app/build.gradle.kts`).
- Privacy-affecting options (anything that contacts a third party) default to **off**, like the YouTube and website link previews.
- Keep edits to upstream files small; put new code in new files where possible, so `git merge upstream/main` stays easy.
- New strings go in `res/values/strings.xml` (English only for now; other locales fall back to it).

## Remotes and building

- `origin` = github.com/jawsh207/twill, `upstream` = github.com/GrapheneOS/Messaging.
- `.github/workflows/build-apk.yml` builds a debug APK on every push and on manual dispatch, and uploads it as the `messaging-debug-apk` artifact. From a cloud session: `gh workflow run build-apk.yml -R jawsh207/twill --ref main`, then poll `gh run view <id> -R jawsh207/twill --json status,conclusion`.
- CI uploads `twill-apk` (perf build: release config with R8, `app.twill.perf`, label "Twill") for everyday use and `twill-debug-apk` (`app.twill.debug`, "Twill d"). Debug builds are unoptimized and coverage-instrumented, so they stutter; judge performance only on the perf build. Test builds are signed with the committed `.github/ci/debug.keystore` (force-added; `*.keystore` is gitignored) so they update each other.
- Cloud sessions usually can't reach Google's Maven repo, so they can't compile locally. Use the workflow to verify builds, and run `ktlint` on changed Kotlin files before pushing.

## Where Twill's features live

- **Boolean settings:** `AppBooleanPref` enum + keys/defaults in `res/values/constants.xml`; read in `AppSettingsRepository`; UI in `ui/appsettings/general/ui/AppSettingsScreen.kt` and `ui/appsettings/privacy/ui/PrivacySettingsScreen.kt`. Code that can't suspend reads prefs through `AppPreferenceReader`.
- **Delay sending:** `ConversationDraftDelegateImpl.sendDraftAfterDelay` (3 s, application scope, cancel via `cancelDelayedSend`) publishes `delayedSend` state; `ConversationScreen.kt` turns it into `LocalDelayedSendUi`, and `ConversationSendActionButton.kt` draws the countdown ring with an X that cancels.
- **Settings placement:** "Delay sending" (first in Advanced) and "Send long messages as MMS" (last in MMS) live on the per-SIM Advanced page, `SubscriptionSettingsScreen.kt`, fed by `AppSettingsViewModel` in `SettingsNavEntries.kt`. They are app-wide prefs, so every SIM's page shows the same values. "Keep unread at top" stays on the main Settings page.
- **Scheduled messages:** storage in `data/scheduled/ScheduledMessageStore.kt` (JSON in private SharedPreferences); alarms and sending in `ScheduledMessageScheduler.kt`; `receiver/ScheduledMessageReceiver.kt` handles the alarm, boot and app update; UI in `ui/conversation/scheduled/`, opened by long-pressing the send button. `ConversationScreen.kt` builds `LocalSendLongPressActions` (Schedule message, plus Switch SIM on multi-SIM phones); `ConversationComposeBar.kt` runs a single action directly or shows a menu for several. Schedule only appears where `LocalScheduledMessagesController` is provided (`ConversationNavEntries.kt`); without it the long press keeps upstream behaviour (SIM selector).
- **Keep unread at top:** `ConversationListOptimisticSnapshotDelegate.withUnreadOnTop` (pinned first, then unread).
- **Website link previews:** `ui/conversation/messages/ui/link/WebLinkPreview.kt` (HTTPS-only fetcher with size/time limits and an in-memory cache); URL picked in `ConversationMessageContentBuilder.kt`; card shown in `ConversationMessageBubble.kt`.
- **Long SMS as MMS:** `datamodel/MessageTextStats.java` marks any multi-part SMS as needing MMS when the pref is on.

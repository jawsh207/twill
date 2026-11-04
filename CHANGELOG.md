# Changelog

What's new in each Twill release. The Release workflow copies the section for the version
being released onto its GitHub release page, and refuses to release a version that has no
section here. Write entries for people using the app, newest version first.

## 1.0.16

- Nostr and Delta Chat notifications now look and work like SMS ones: the conversation's last
  few messages with names and pictures, and Mark as read, Reply and Delete buttons. They show
  in Android's Conversations section, with the same per-conversation settings as SMS.
- Replying from a notification works even when Twill isn't connected in the background; it
  connects just long enough to send.

## 1.0.15

- Nostr Start chat suggests people as you type a name, address or npub: people you follow,
  talk to, or have replied to, mentioned, reacted to or zapped, including in other Nostr apps.
  Addresses show once they're confirmed, with a check mark.
- New setting under Nostr > Finding people: "Search relays for people" also finds people you
  have no connection with. It's off by default, because the search relay sees what you type.

## 1.0.14

- Delta Chat groups have their own QR code: open the group's info and tap "Invite with QR
  code". Anyone who scans it with Delta Chat or Twill joins the group.
- Bottom bar: swipe left and right to change tabs, and Back returns to the SMS tab before
  leaving the app.
- Swiping an SMS conversation to archive it is gone (archive it from the selection bar after a
  long press). Swiping right to mark it read or unread stays.

## 1.0.13

- Delta Chat QR codes, as in Delta Chat: show your own QR code for someone to scan (in person
  or over a video call), or scan theirs with the camera to start a chat or join a group.
  Find it in Delta Chat Settings, or under Start chat > Delta Chat.

## 1.0.12

- Same look as Dialtone and Contacts: Google Sans Flex for all text, wallpaper colours with
  slightly lighter pages and a search bar in the search-bar colour. Home screen widgets use
  the same colours.
- New option in Settings: Bottom bar. When more than one service is on, the inbox shows a
  tab for each (SMS, Nostr, Delta Chat) with unread counts, instead of sections. Start chat
  starts a chat in the tab's service.

## 1.0.11

- Delta Chat chats now show their messages; they opened empty before, which also hid
  failed messages and their retry.
- New inbox look: every conversation is its own card, grouped under SMS, Nostr and Delta
  Chat.
- A "Search messages" bar at the top searches conversation names and messages across SMS,
  Nostr and Delta Chat; opening a result jumps to that message. Settings is at the right
  of the bar.
- Archived conversations and Blocked contacts moved to SMS Settings.
- Section headers are simpler: the collapse arrow is on the right, and new chats start from
  a long press on Start chat.
- Blocked contacts matches the new look.

## 1.0.10

- Fixes from GrapheneOS Messaging:
  - Texts that reach a SIM from another country without a country code are matched to the
    right contact, and conversations an older version split off for them are merged back.
  - Blocked and archived conversations stay blocked and archived if the message database
    has to be rebuilt, and messages are kept when going back to an older version.
  - Senders you can't reply to (such as short codes and named senders) show a note instead
    of the message box, and their notifications no longer offer Reply.
  - Tapping a link keeps the part after "#", so it opens at the right spot.
- Library updates.
- The app is about 12 MB (around 28%) smaller and its code is better optimized.

## 1.0.9

- New home screen widgets in the same system colours as the app. "Conversations" lists your
  SMS, Nostr and Delta Chat conversations together, newest first, with a button to start a
  chat. "Conversation" shows the latest messages of any one conversation (SMS, Nostr or
  Delta Chat); long-press it to pick a different one. Previews in the widget picker show
  the real look instead of old screenshots, and the app is slightly smaller.

## 1.0.8

- New look that follows your system colours exactly like Settings: grey with "Other colors",
  tinted with "Wallpaper colors", light and dark. Pages use the same background as Settings,
  and rows, cards, incoming bubbles, menus and dialogs the same lighter surface; your accent
  colour is only used for highlights such as buttons, switches and your own bubbles.
- Material 3 Expressive motion.
- Settings screens group their options into rounded cards, like Android's Settings app.

## 1.0.7

- The app icon's background is now blue instead of green.
- Setup and the rest of the app call the app "Twill" instead of "Messaging" (permissions
  screen, share sheet, widgets, notifications and default-SMS-app prompts).
- SMS/MMS notifications have a "Delete" button next to "Mark as read" and "Reply". It deletes
  the messages shown in the notification. (When an MMS needs downloading, "Download" takes its
  place, since Android shows at most three buttons.)

## 1.0.6

- Search inside a conversation: tap the search button at the top of any SMS/MMS, Nostr or
  Delta Chat conversation. Matches are highlighted, and the arrows step through older and
  newer ones, loading earlier messages as needed.
- New app icon: the same two overlapping speech bubbles, now in the bubble shape used by
  Twill's notification icons.

## 1.0.5

- Clearer "Start chat" menu: "SMS/MMS conversation", "Nostr chat or group" and "Delta Chat
  chat or group".
- SMS/MMS has a new icon in the same speech-bubble style as Nostr and Delta Chat, in its
  notifications and in the "Start chat" menu.
- The "Start chat" button uses the same speech-bubble icon, with a plus.

## 1.0.4

- The long-press "Start chat" menu lists each service with its icon in the same colour as
  SMS/MMS. Picking Nostr or Delta Chat then asks whether to start a chat or a group.

## 1.0.3

- The long-press "Start chat" menu groups each service's options under its name, with the
  service's own icon (the same one its notifications use).

## 1.0.2

- Long press the "Start chat" button to choose where to start a chat: SMS/MMS, Nostr or
  Delta Chat. A normal press still starts an SMS/MMS conversation.

## 1.0.1

- Delta Chat Settings now say "relay" instead of "chatmail", matching Delta Chat's own app.

## 1.0.0

First release of Twill, a messaging app based on GrapheneOS Messaging. It installs alongside
the system Messaging app.

- **SMS/MMS** with extra options: delay sending (with a countdown to cancel), scheduled
  messages, keep unread conversations at the top, website link previews (off by default) and
  sending long messages as MMS.
- **Use Twill without SMS:** turn SMS/MMS off and use Twill only for Nostr and Delta Chat,
  with another app handling texts.
- **Nostr private messages** (NIP-17): one-to-one and group chats, sign in with Amber or a
  key stored on the phone, NIP-05 names, attachments and profile pictures.
- **Delta Chat:** encrypted chat over email using the official Delta Chat core. Create a free
  profile, use a setup code, or sign in with an email account.
- One inbox with a collapsible section per protocol, its own notification icon for each, and
  a choice of background delivery (always connected or check every 15 minutes).

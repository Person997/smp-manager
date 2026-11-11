# SMP Manager 1.1.2

One Paper plugin manages a shared Minecraft/Discord queue, linked accounts, player thresholds and a frozen world. The Discord bot runs inside the Minecraft process. No proxy, second Minecraft server, controller console or separate bot host is required.

This build targets **Minecraft Java 26.3, Paper 26.3 and Java 25**. It is not a Spigot, Fabric, Bedrock or older-Paper build. Players use an ordinary compatible Java client; no client mod is needed.

Download the ready-to-install **SMP-Manager.jar** from [GitHub Releases](https://github.com/Person997/smp-manager/releases/latest). The repository contains source code, tests, build tooling and an unconfigured settings example.

Version 1.1.2 uses the queue headline **World closed**, keeps menus visible between button actions, and uses the short disconnect reason **Disconnected.** Disconnect waits for the client to acknowledge menu closure before ending the network connection. It also handles duplicate Run clicks, late replies after Back, replacement connections, whitelist removal while queued and canceled requests during server lag.

The normal menu no longer closes itself on each click: it uses Paper's [`DialogAfterAction.NONE`](https://jd.papermc.io/paper/26.3/io/papermc/paper/registry/data/dialog/DialogBase.DialogAfterAction.html), so the underlying Joining world screen is not revealed between menu actions. A real world entry/reconfiguration may still display Minecraft's loading screen. Disconnect clears the dialog and requests an empty, namespaced cookie response as a packet-processing acknowledgement; it does not store a cookie or use its contents. Only after that response does it end the connection. A two-second timeout prevents an unresponsive client from retaining the connection indefinitely. Vanilla Minecraft then shows its disconnected screen with Back to Server List. A server plugin cannot skip that client screen; doing so requires a client modification.

## Install on Paper / PebbleHost

1. Stop the Minecraft server using its panel. Back up the world before changing plugins.
2. Select **Paper 26.3** and **Java 25** in the host's settings. If either option is unavailable, ask the host before installing this build.
3. Put `SMP-Manager.jar` from the release into the server's `plugins` folder. Remove the former Quorum Paper plugin if migrating.
4. In `server.properties`, enable `online-mode=true`, `white-list=true` and `enforce-whitelist=true`. Add the real Java accounts with the console command `whitelist add <name>`.
5. Start once. The plugin creates `plugins/SMPManager/settings.properties`, `data.db` and `verification.key`. The world starts frozen. Discord is disabled by default, so a new download works without anyone else's bot token.
6. Stop, edit the properties file, then start again. See the settings and Discord instructions below. Changes take effect after a full restart; do not use `/reload` or plugin unloaders.

Use the panel's normal Start/Stop controls. The plugin needs no extra listening port. It needs outbound HTTPS/WebSocket access to Discord and HTTPS access to Minecraft account services. Keeping a frozen world still uses the Minecraft process and its allocated memory; it avoids normal world simulation rather than shutting down the allocation.

To stop the entire Minecraft server and bot, use the hosting panel's Stop button or enter `stop` in the server console and let saving finish. `/smp close` closes gameplay while keeping the server and bot online.

## How it works

- **Closed:** the whole server's world simulation is frozen, including all dimensions. Authenticated, whitelisted players see a native Minecraft queue screen during the connection's configuration stage. There is no dirt lobby and no playable waiting world. The screen offers Commands and Disconnect. The initial vanilla connection screen still appears briefly before the server can send its own screen.
- **Shared queue:** connected waiting accounts and valid Discord reservations count toward the opening threshold. The same Minecraft UUID counts once even if it is present in both. Minecraft participation is automatic; there are no Minecraft join/leave queue commands. Disconnecting removes the live connection, but an existing Discord reservation keeps its own expiry.
- **Opening:** five unique queued accounts open the world by default. Waiting connections enter the world, and Discord announces the opening. The normal session has a three-minute initial join window so reserved players can connect. At its end, at least four eligible players must actually be online; otherwise the world closes. Discord reservations do not count as online players.
- **Open:** new connections go directly into the world. No queue screen or action bar is shown. Eligible players are authenticated, whitelisted, non-spectator world players. Discord linking is needed for Discord reservations, not for an already whitelisted Minecraft player to play. Optional AFK exclusion is disabled by default.
- **Low population:** after the join window, dropping below four starts a five-minute grace period. Restoring four cancels the closing. A later drop starts a fresh grace period. These numbers are configurable.
- **Chat warnings:** only while the online threshold is unmet, players receive warnings at 10 minutes, 5 minutes, 1 minute, then 10 through 1 seconds remaining. Milestones longer than the configured timer are skipped. This applies to both the initial join window and closing grace. The ordinary five-minute grace announces: `Player threshold not met. Closing world in 5 minutes.` There is no persistent action bar.
- **Closing:** the plugin freezes simulation, saves the worlds and players, and moves connected players back to the native queue screen. Their inventories and positions remain saved. After closing, the same waiting connections alone cannot immediately reopen the world: a fresh connection, an explicit Discord reservation action, or `/smp reset` re-arms the normal queue. `/smp autopass` can open it immediately.
- **Autopass:** an authorized admin bypasses the opening threshold for one session. Low population receives one hour by default. Repeating autopass in that session does not extend its timer. If population recovers and later falls again, the session still uses the override grace. Closing ends the override; the next session uses normal rules.
- **Restart/faults:** a normal `stop` saves cleanly and the next boot starts closed. An unconfirmed exit during an open session or incomplete save produces a frozen error state. Check the logs/storage, then an admin can use `/smp reset`. Autopass cannot skip an unresolved fault.

The freeze controls all worlds in this Paper instance. Use a dedicated SMP instance rather than combining unrelated minigames that must keep ticking. Administrative tools or other plugins that explicitly change blocks/time can still make changes; SMP Manager controls normal simulation and player access.

## Settings

Edit `plugins/SMPManager/settings.properties` **while stopped**, then restart. Numeric durations use the units in their names. Invalid configuration keeps the world frozen and logs the setting to repair.

| Property | Default | Meaning |
| --- | --- | --- |
| `players-required-to-open` | `5` | Unique waiting accounts/reservations needed to open; 1–200. |
| `minimum-online-players` | `4` | Minimum eligible world players to stay open; closes when **below** this count. Must be 1 through the opening threshold. |
| `grace-period-seconds` | `300` | Normal low-population grace; 1–604800 seconds. |
| `startup-join-window-seconds` | `180` | Time for people to join after normal automatic opening; 1–86400 seconds. |
| `autopass-grace-seconds` | `3600` | One-session override's low-population grace; 1–604800 seconds. |
| `queue.reservation-minutes` | `60` | Discord reservation lifetime, 1–1440 minutes. |
| `queue.renew-cooldown-seconds` | `300` | Minimum time between reservation creation/renewals. |
| `afk.enabled` | `false` | Exclude inactive world players from the online threshold if enabled. |
| `afk.timeout-seconds` | `300` | AFK timeout, at least 60 seconds. Movement/looking, interaction, chat and gameplay actions renew activity. |
| `admins.autopass-uuids` | empty | Comma-separated authenticated UUIDs allowed only to use autopass, including from the queue screen. Does not grant account administration or close/reset. |
| `discord.enabled` | `false` | Enable the embedded bot. |
| `discord.token` | empty | Private **bot token**, not an application ID or your Discord password. |
| `discord.guild-id` | empty | Discord server ID. |
| `discord.status-channel-id` | empty | Text channel containing the persistent status message. |
| `discord.notification-channel-id` | empty | Text channel for opening/closing/fault notices; set it explicitly. |
| `discord.admin-role-ids` | empty | Comma-separated role IDs authorized for bot administration; members must also have Manage Server. |
| `discord.status-update-seconds` | `10` | Persistent status update interval; minimum 5 seconds. |
| `verification.request_cooldown_seconds` | `30` | Cooldown for requesting link codes; minimum 30 seconds. |
| `verification.allow_manual_admin_verification` | `true` | Allow reviewed, audited manual account associations. |

For example, requiring three people to open and two to stay open with a ten-minute grace means `players-required-to-open=3`, `minimum-online-players=2` and `grace-period-seconds=600`.

## Connect Discord

1. Create your own application and bot in the [Discord Developer Portal](https://discord.com/developers/applications). Each independently hosted server should use its own bot; run only one instance per bot token.
2. Install it into your Discord server with the `bot` and `applications.commands` scopes. Give it **View Channels, Send Messages, Embed Links and Read Message History** in the two selected text channels. Administrator and privileged gateway intents are not required.
3. Enable Developer Mode in Discord and copy the server, channel and admin-role IDs. Give your intended admins that role and Manage Server permission. Both are required; merely listing a role does not grant it Manage Server.
4. Stop Paper, set the `discord.*` properties, including `discord.enabled=true`, and start it. The bot registers its slash commands and creates or reuses one status message. A Discord outage does not stop Minecraft world control.
5. To link accounts, run Discord `/link`. It returns a private six-digit code. In Minecraft, run `/smp link <code>`; when closed, use the queue screen's **Commands** input. Codes expire after five minutes, work once and are protected against repeated guessing. Each Discord and Minecraft account can have only one active association.

The included `settings.example.properties` contains no bot token or account identifiers. Keep a configured `settings.properties`, `verification.key` and `data.db` private.

## Minecraft commands

All SMP Manager Minecraft commands start with `/smp`. A bare `/smp` shows usage; `/smp status` displays status. The console uses the same commands without the leading slash. While queued, enter them through **Commands → Minecraft command → Run command**.

| Command | Who | Purpose |
| --- | --- | --- |
| `/smp status` | Everyone | World/queue status, relevant timer and thresholds. |
| `/smp link <code>` | Player | Redeem that player's private Discord link code. |
| `/smp help` | Everyone | Show commands available to the sender. |
| `/smp autopass` | Admin or autopass grant | Open/override this session with the configured longer grace. |
| `/smp close` | Admin | Save/freeze the world and return players to its queue. |
| `/smp reset` | Admin | Verify saving and environment, clear a fault/close hold, re-arm normal opening. Does not delete world or account data. |
| `/smp verify <Minecraft name> <Discord user ID>` | Admin | Prepare a manual ownership assertion and show a review token. |
| `/smp confirm <token>` | Same admin | Approve that reviewed association within five minutes. |
| `/smp unverify <Discord user ID>` | Admin | Revoke the link and its pending codes/reservation. |
| `/smp list [Discord user ID] [page]` | Admin | List links or inspect one account. Pages start at 0. `/smp list 1` requests page 1. |
| `/smp pending` | Admin | Inspect pending requests without revealing raw link codes. |
| `/smp history [Discord user ID] [page]` | Admin | Inspect association audit history. Pages start at 0. |

Full administration accepts **`smpmanager.admin` or the previous `quorum.admin`** permission. Autopass also accepts **`smpmanager.autopass` or `quorum.autopass`**, or an entry in `admins.autopass-uuids`. These permission nodes default to server operators. Listing an autopass UUID grants no other admin commands. A permission plugin may grant the nodes to players after they enter the world; before the first world join, the queue screen can determine native operator status and the configured autopass UUID grant.

The old root commands `/queue`, `/link`, `/autopass`, `/smpadmin` and `/quorummaintenance` are not registered by this plugin. Another installed plugin may independently own those names.

## Discord commands

Discord keeps reservation commands because someone can reserve without Minecraft running. All replies containing account data or command results are private.

| Command | Purpose |
| --- | --- |
| `/status`, `/queue status` | Show world/queue status. |
| `/players` | Show confirmed eligible world players. |
| `/link`, `/unlink` | Request a private link code or revoke your association. |
| `/queue join` | Reserve for the closed world; repeated joins do not extend expiry. |
| `/queue renew` | Explicitly renew after the configured cooldown. |
| `/queue leave` | Remove only the Discord reservation; a connected Minecraft player still counts. |
| `/help` | Explain the bot commands. |
| `/close` | Admin: save/freeze the world and return connections to queue. |
| `/admin autopass`, `/admin reset` | Admin: same one-session override and reset as Minecraft. |
| `/admin verify minecraft:<name> discord:<member>` | Admin: review a manual association, then use the private approval button. |
| `/admin unverify discord:<member>` | Admin: revoke an association. |
| `/admin verification status`, `list`, `pending`, `history` | Admin: inspect links, pending requests and audits using the slash-command fields. |

`/close` and `/admin` require a configured admin role plus Manage Server (or Discord Administrator) permission. Pending manual approval must be confirmed by the same admin who prepared it. Reservation expiry notices attempt a DM; blocked DMs do not block the system.

## Data, migration and troubleshooting

For a legacy SMP Quorum installation, stop all its services first and keep a complete backup. Copy the survival world/whitelist to the direct Paper server; disable Velocity forwarding and restore `online-mode=true`. Copy the stopped legacy `quorum.db` as `plugins/SMPManager/data.db` with any SQLite sidecars, and place the old `QUORUM_VERIFICATION_KEY` value in `verification.key`. Configure the existing bot IDs/token privately. Do not run the old controller/bot against the same database/token. Fresh installs need none of these migration steps.

- **World frozen after startup:** expected while below the opening threshold. Use `/smp status`; an authorized `/smp autopass` opens a test session.
- **Cannot use an admin command:** grant the relevant permission or use the server console. Being linked, whitelisted or allowed to autopass does not grant all administration.
- **Queue does not immediately reopen after closing:** expected until a new connection/reservation action or admin reset re-arms it. This prevents a close/reopen loop with the same clients.
- **Configuration or save fault:** fix the setting/storage issue shown in `logs/latest.log`. Restart for configuration changes; use `/smp reset` after repairing a runtime fault. Reset does not bypass online authentication or whitelist requirements.
- **Bot offline:** confirm the token, IDs, installed bot and channel permissions. Minecraft still works if Discord is unavailable. Stopping the entire Paper process also stops the embedded bot.
- **Old commands still appear:** remove the old plugin JAR and restart fully. Check other plugins that may own those commands.

Back up the world and plugin data together while stopped. Retain the private key for persisted codes and the database for links/audits. This plugin saves the world on close; it is not a replacement for scheduled backups.

## Build and validation

Clone this repository and install Java 25, then run `gradlew.bat build` (Windows) or `./gradlew build` (Linux/macOS). This builds the plugin and runs the tests. The installable shaded JAR is `build/libs/SMP-Manager.jar`; the `thin` JAR is not the release. Dependency locks and verification metadata are included.

Automated tests cover thresholds, unique account counts, reservation expiry/renewal, normal and override grace, closing warnings, close/reopen holds, crash recovery, permissions, verification and legacy database migration. See `VALIDATION.md` for the completed live Paper checks and their limits.

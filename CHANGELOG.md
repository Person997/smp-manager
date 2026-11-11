# Changelog

## 1.1.2

- Run world management and the Discord bot inside one Paper plugin.
- Save and freeze closed worlds while keeping the server process online.
- Show a native queue screen until the configured opening threshold is met.
- Put all Minecraft commands under `/smp`; add `/smp close` and Discord `/close`.
- Configure opening/staying-open thresholds, grace periods, reservations, optional AFK exclusion and Discord integration through a properties file.
- Support one-session autopass with a longer low-population grace.
- Send threshold warnings in chat without a persistent action bar.
- Preserve legacy account links, verification codes and audit history.
- Prevent stale connections, repeated clicks and delayed command replies from disrupting the queue.
- Wait for a client acknowledgement of menu closure before disconnecting, with a bounded timeout and asynchronous connection-close cleanup.

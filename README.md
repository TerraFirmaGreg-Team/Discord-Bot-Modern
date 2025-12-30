# TFG Field Guide Discord Bot

A Discord bot that looks up TerraFirmaGreg Field Guide pages and posts embeds with the title, summary, and link.

## Features

- `/fgtop`: Shows a list of curated top field guide pages.
- `/fgpath <path>`: Uses a raw path (e.g., `/fgpath tfg_ores/earth_vein_index`).
- `/fgsearch <query>`: Searches the through entries for titles containing the `query` keyword.

## Setup for Forks

1. Create a Discord bot at the Developer Portal, invite it to your server, and copy the token.
2. In this folder, create an `.env` file and set `DISCORD_TOKEN` and `DISCORD_CLIENT_ID`.

> keep .env private.

```env
DISCORD_TOKEN="your_bot_token"
DISCORD_CLIENT_ID="your_client_id"
```

## Install & Run

```bash
cd #directory
npm install                # first time use
npm run commands:register  # first time use
npm start
```

Make sure your bot has the "Message Content Intent" enabled in the Developer Portal, since this bot uses message-based commands.

## Notes

- Global commands can take up to ~1 hour to propagate across Discord. They will appear in every server once your bot is invited.
- Invite URL: set scopes bot and `applications.commands`. Minimal permissions to send messages and embed links.
- `DEV_MODE` in `index.js` can be set to `true` to show extra console debug logs and to register commands through guild registration instead of globally.

For example:
https://discord.com/api/oauth2/authorize?client_id=YOUR_CLIENT_ID&scope=bot%20applications.commands&permissions=18432

## License

- Licensed under LGPL-3.0-or-later.
- See [LICENSE](LICENSE) for the full license text.
- SPDX-License-Identifier: LGPL-3.0-or-later

# TerraFirmaGreg Discord Bot

Discord bot for accessing the TerraFirmaGreg Field Guide with multi-language support and interactive search.

## Features

- **Field Guide Integration** - Browse TerraFirmaGreg Field Guide pages directly in Discord
- **Multi-language Support** - Available in 9 languages (English, Japanese, Korean, Portuguese, Russian, Ukrainian, Simplified/Traditional Chinese)
- **Interactive Search** - Fast search with pagination and result selection
- **Rich Embeds** - Beautifully formatted guide content with share buttons
- **Rate Limiting** - Built-in protection against spam

## Commands

| Command | Description |
|---------|-------------|
| `/guide top` | Quick access to most useful guide entries |
| `/guide search <query>` | Search guide by keywords |
| `/guide path <path>` | Fetch specific page by URL path |
| `/guide scare` | New player introduction with field guide info |

All commands support optional `language` parameter for localization.
- English (en_us) - default
- 日本語 (ja_jp)
- 한국어 (ko_kr)
- Português (pt_br)
- Русский (ru_ru)
- Українська (uk_ua)
- 简体中文 (zh_cn)
- 香港繁體 (zh_hk)
- 繁體中文 (zh_tw)

## Quick Start

### Prerequisites
- Java 17+
- Discord Bot Token

### Setup

1. **Clone the repository**
```bash
git clone https://github.com/TerraFirmaGreg-Team/Discord-Bot.git
cd Discord-Bot
```

2. **Configure environment**
Create `.env` file:
```env
DISCORD_TOKEN=your_bot_token
DISCORD_CLIENT_ID=your_client_id
DISCORD_GUILD_ID=your_guild_id  # Optional for development
RATE_LIMIT_MS=3000  # Optional rate limit
```

3. **Build and run**
```bash
# Build
./gradlew.bat clean build

# Register slash commands (run once)
java -cp "build\libs\*" team.terrafirmagreg.bot.RegisterCommands

# Start bot
./gradlew.bat run
```

## Development

- **Dev Mode**: Set `DEV_MODE = true` in `Main.java` for detailed logging and instant command updates
- **Build**: Uses Gradle with Shadow plugin for fat JAR creation
- **Dependencies**: JDA (Discord API), JSoup (HTML parsing), Gson (JSON)

## Architecture

- **Main.java** - Core bot logic and command handlers
- **Scraper.java** - Field Guide web scraping and content parsing
- **Locales.java** - Multi-language support
- **RegisterCommands.java** - Slash command registration

## License

See LICENSE file for details.

## Contributing

Contributions welcome! Please ensure code follows existing patterns and includes proper error handling.

# TFG Field Guide Bot

Discord bot to look up TerraFirmaGreg Field Guide pages and display embeds.

## Prerequisites

- **Java 17** or higher. [Download](https://adoptium.net/)
- No Maven installation needed.

## Setup

1. Create a `.env` file in the project root with your Discord credentials:

```env
DISCORD_TOKEN=your_bot_token_here
DISCORD_CLIENT_ID=your_client_id_here
DISCORD_GUILD_ID=your_guild_id_here  # Optional, for DEV_MODE guild command registration
RATE_LIMIT_MS=3000  # Optional, rate limit in milliseconds
```

2. Build the project:

```powershell
# Windows PowerShell (using wrapper - no Maven installation needed)
.\mvnw.cmd clean package
.\mvnw.cmd dependency:copy-dependencies

# Or if you have Maven installed globally
mvn clean package
```

## Running the Bot

### Step 1: Register Slash Commands

Run this once initially, or whenever you change command definitions:

```powershell
java -cp "target/classes;target/dependency/*" team.terrafirmagreg.bot.RegisterCommands
```

### Step 2: Run the Bot

```powershell
# Option A: Using Maven wrapper
.\mvnw.cmd compile exec:java -D"exec.mainClass=team.terrafirmagreg.bot.Bot"

# Option B: Run the JAR directly (after building with 'mvnw.cmd package')
java -jar target\terrafirmagreg-bot-0.1.0.jar
```

## Commands

| Command         | Description                                                      |
|----------------|------------------------------------------------------------------|
| `/guide top`   | Display a list of the most useful field guide entries             |
| `/guide search`| Search the guide for pages matching your keywords (requires query)|
| `/guide path`  | Fetch a page by URL path (requires path argument)                |
| `/guide scare` | New player jump scare - shows the field guide info               |

All `/guide` subcommands support a `language` option to select different locales:
- English (en_us) - default
- 日本語 (ja_jp)
- 한국어 (ko_kr)
- Português (pt_br)
- Русский (ru_ru)
- Українська (uk_ua)
- 简体中文 (zh_cn)
- 香港繁體 (zh_hk)
- 繁體中文 (zh_tw)

## Development Mode

Set `DEV_MODE = true` in `Bot.java` to:
- Enable detailed terminal logging
- Use guild-based command registration (instant updates vs. global which can take up to an hour)


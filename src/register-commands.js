"use strict";
const { SlashCommandBuilder, REST, Routes } = require('discord.js');
const { getLanguageChoices } = require('./locales');
const dotenv = require('dotenv');

dotenv.config();

const clientId = process.env.DISCORD_CLIENT_ID;
const token = process.env.DISCORD_TOKEN;
const guildId = process.env.DISCORD_GUILD_ID;
const { DEV_MODE } = require('./index');
const rawDev = process.env.DEV_MODE;

if (!clientId || !token) {
  console.error('Missing DISCORD_CLIENT_ID or DISCORD_TOKEN in .env');
  process.exit(1);
}

const commands = [
  // Ping command for testing.
  // new SlashCommandBuilder()
  //   .setName('ping')
  //   .setDescription('Test'),
  
  // Field Guide Commands.
  new SlashCommandBuilder()
    .setName('fgpath')
    .setDescription('Fetch a page by URL path.')
    .addStringOption(opt =>
      opt.setName('path')
        .setDescription('Example: "mechanics/animal_husbandry"')
        .setRequired(true)
    )
    .addStringOption(opt =>
      opt.setName('language')
        .setDescription('Locale (default en_us)')
        .setRequired(false)
        .addChoices(...getLanguageChoices())
    ),
  new SlashCommandBuilder()
    .setName('fgtop')
    .setDescription('Top Field Guide links')
    .addStringOption(opt =>
      opt.setName('language')
        .setDescription('Locale (default en_us)')
        .setRequired(false)
        .addChoices(...getLanguageChoices())
    ),
  new SlashCommandBuilder()
    .setName('fgsearch')
    .setDescription('Search the guide!')
    .addStringOption(opt =>
      opt.setName('query')
        .setDescription('Example: "climate"')
        .setRequired(true)
    )
    .addStringOption(opt =>
      opt.setName('language')
        .setDescription('Locale (default en_us)')
        .setRequired(false)
        .addChoices(...getLanguageChoices())
    ),
].map(cmd => cmd.toJSON());

(async () => {
  try {
    const rest = new REST({ version: '10' }).setToken(token);
    console.log(`[Commands] Config DEV_MODE=${DEV_MODE} raw=${rawDev ?? 'unset'} guildId=${guildId ?? 'unset'}`);
    if (DEV_MODE && guildId) {
      console.log(`[Commands] DEV_MODE=true: Registering GUILD slash commands for guild ${guildId}...`);
      await rest.put(
        Routes.applicationGuildCommands(clientId, guildId),
        { body: commands }
      );
      console.log('[Commands] Guild registration complete (instant update).');
    } else {
      console.log('[Commands] Registering GLOBAL slash commands...');
      await rest.put(
        Routes.applicationCommands(clientId),
        { body: commands }
      );
      console.log('[Commands] Global registration complete (may take a few minutes).');
    }
  } catch (err) {
    console.error('[Commands] Registration failed:', err);
    process.exit(1);
  }
})();

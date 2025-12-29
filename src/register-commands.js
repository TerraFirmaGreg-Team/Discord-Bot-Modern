"use strict";
const { SlashCommandBuilder, REST, Routes } = require('discord.js');
const dotenv = require('dotenv');

dotenv.config();

const clientId = process.env.DISCORD_CLIENT_ID;
const token = process.env.DISCORD_TOKEN;

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
        .setDescription('Example: "tfg_tips/blast_furnace_tips"')
        .setRequired(true)
    ),
  new SlashCommandBuilder()
    .setName('fgtop')
    .setDescription('Top Field Guide links'),
  new SlashCommandBuilder()
    .setName('fgsearch')
    .setDescription('Search the guide!')
    .addStringOption(opt =>
      opt.setName('query')
        .setDescription('Example: "climate"')
        .setRequired(true)
    ),
].map(cmd => cmd.toJSON());

(async () => {
  try {
    const rest = new REST({ version: '10' }).setToken(token);
    console.log('[Commands] Registering GLOBAL slash commands...');
    await rest.put(
      Routes.applicationCommands(clientId),
      { body: commands }
    );
    console.log('[Commands] Registration complete.');
  } catch (err) {
    console.error('[Commands] Registration failed:', err);
    process.exit(1);
  }
})();

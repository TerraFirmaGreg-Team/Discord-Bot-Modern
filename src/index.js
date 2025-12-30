const DEV_MODE = false; // set to true to enable terminal logging and guild based command registration
module.exports = { DEV_MODE };

  const { Client, GatewayIntentBits, Partials, ActionRowBuilder, StringSelectMenuBuilder, ButtonBuilder, ButtonStyle, EmbedBuilder } = require('discord.js');
  const dotenv = require('dotenv');
  const { BASE, DEFAULT_LANG, LANGS, fetchGuideEmbed, searchGuideFast, fetchPageTitle } = require('./scraper');
  const crypto = require('crypto');
  const searchSessions = new Map();
  // Rate limiting per user per user.
  const RATE_LIMIT_MS = parseInt(process.env.RATE_LIMIT_MS || '3000', 10);
  const _rl = new Map();

  function checkAndTouch(userId, key) {
    if (!userId || !key) return 0;
    const now = Date.now();
    const k = `${userId}:${key}`;
    const last = _rl.get(k) || 0;
    const diff = now - last;
    if (diff < RATE_LIMIT_MS) return RATE_LIMIT_MS - diff;
    _rl.set(k, now);
    return 0;
  };

  /**
   * Builds select menu options for a page of results (25 max).
   * @param {Array<{ title: string, url: string }>} results All search results.
   * @param {number} page Page index.
   * @returns {Array<{ label: string, value: string, description?: string }>} Options for the select menu.
   */
  function buildSearchOptions(results, page) {
    const FRAGMENT_BLACKLIST_SUBSTRINGS = ['glb-viewer','nav-primary','navbar-content','lang-dropdown-button','bd-theme','bd-theme-text','glb-viewer'];
    const start = (Math.max(1, page) - 1) * 25;
    const slice = results.slice(start, start + 25);
    const options = slice
      .map(r => {
        const rel = r.url.startsWith(BASE) ? r.url.slice(BASE.length) : r.url;
        if (rel.includes('#')) {
          const frag = rel.split('#').pop();
          const lc = (frag || '').toLowerCase();
          if (FRAGMENT_BLACKLIST_SUBSTRINGS.some(sub => lc.includes(sub))) return null;
        }
        return {
          label: (r.title || 'Result').slice(0, 100),
          value: rel.length <= 100 ? rel : null,
          description: rel.slice(0, 100)
        };
      })
      .filter(o => o && o.value !== null);
    return options;
  }

  /**
   * Select menu for current page and Prev/Next buttons.
   * @param {string} token Unique session token.
   * @param {number} page Current page index.
   * @param {number} totalPages Total number of pages.
   * @param {Array} options Select options for current page.
   * @param {string} placeholder Placeholder text.
   * @returns {Array} Array of component rows.
   */
  function buildSearchComponents(token, page, totalPages, options, placeholder) {
    const select = new StringSelectMenuBuilder()
      .setCustomId('fgsearch-select')
      .setPlaceholder(placeholder)
      .addOptions(options);
    const row1 = new ActionRowBuilder().addComponents(select);
    if (totalPages <= 1) return [row1];
    const prev = new ButtonBuilder()
      .setCustomId(`fgsearch-prev:${token}:${page}`)
      .setLabel('Prev')
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page <= 1);
    const next = new ButtonBuilder()
      .setCustomId(`fgsearch-next:${token}:${page}`)
      .setLabel('Next')
      .setStyle(ButtonStyle.Secondary)
      .setDisabled(page >= totalPages);
    const row2 = new ActionRowBuilder().addComponents(prev, next);
    return [row1, row2];
  }

  if (require.main === module) {
    dotenv.config();

    const client = new Client({
      intents: [
        GatewayIntentBits.Guilds
      ],
      partials: [Partials.Channel]
    });

  if (DEV_MODE){ 
  // Log IDs during testing.
    client.once('clientReady', () => {
      console.log(`[FieldGuideBot] Logged in as ${client.user.tag}`);
      try {
        console.log(`[FieldGuideBot] Bot user id: ${client.user.id}`);
        if (process.env.DISCORD_CLIENT_ID) {
          console.log(`[FieldGuideBot] Env client id: ${process.env.DISCORD_CLIENT_ID}`);
          if (process.env.DISCORD_CLIENT_ID !== client.user.id) {
            console.warn('[FieldGuideBot] WARNING: DISCORD_CLIENT_ID mismatch.');
          }
        }
      } catch {}
    });

    // Log all interactions during testing.
    client.on('interactionCreate', (interaction) => {
      try {
        console.log(`[FieldGuideBot] Interaction received: type=${interaction.type} command=${interaction.commandName || ''} isChatInput=${interaction.isChatInputCommand?.() ? 'true' : 'false'} replied=${interaction.replied} deferred=${interaction.deferred}`);
      } catch {}
    });

    // ping pong ping pong ping pong.
    client.on('interactionCreate', async (interaction) => {
      try {
        if (interaction.isChatInputCommand && interaction.isChatInputCommand() && interaction.commandName === 'ping') {
          return interaction.reply({ content: 'pong', flags: 64 });
        }
      } catch (e) {
        console.error('[FieldGuideBot] /ping handler error:', e);
      }
    });
  };

  client.on('interactionCreate', async (interaction) => {
    if (!interaction.isChatInputCommand?.()) return;
    try {
      const rem = checkAndTouch(interaction.user?.id, `cmd:${interaction.commandName}`);
      if (rem > 0) {
        const wait = Math.ceil(rem / 1000);
        try { return interaction.reply({ content: `Please wait ${wait}s before using /${interaction.commandName} again.`, flags: 64 }); } catch {}
        return;
      };
      // `/fgpath`: fetch and display a guide page by the url path given.
      if (interaction.commandName === 'fgpath') {
        const path = interaction.options.getString('path', true);
        const langOpt = interaction.options.getString('language') || DEFAULT_LANG;
        const selectedLang = LANGS.includes(langOpt) ? langOpt : DEFAULT_LANG;
        await interaction.reply({ content: 'Working on it...', flags: 64 });
        try {
          const embed = await fetchGuideEmbed(path, selectedLang);
          const shareRow = new ActionRowBuilder().addComponents(
            new ButtonBuilder().setCustomId('fg-share').setLabel('Share link').setStyle(ButtonStyle.Primary)
          );
          return interaction.editReply({ embeds: [embed], components: [shareRow] });
        } catch (e) {
          DEV_MODE && console.error('[FieldGuideBot] fgpath error:', e);
          try { return interaction.editReply({ content: 'Failed to fetch that page.' }); } catch {}
        };
      };

      // `/fgtop`: present a selector for the most important field guide links for quick access.
      if (interaction.commandName === 'fgtop') {
        await interaction.reply({ content: 'Choose a link…', flags: 64 });
        try {
          const langOpt = interaction.options.getString('language') || DEFAULT_LANG;
          const selectedLang = LANGS.includes(langOpt) ? langOpt : DEFAULT_LANG;
          const langBase = `${BASE}${selectedLang}/`;
          const targets = [
            { emoji: '📙', url: `${langBase}` },
            { emoji: '⛏️', url: `${langBase}tfg_ores.html` },
            { emoji: '🌎', url: `${langBase}the_world/geology.html` },
            { emoji: '🐖', url: `${langBase}mechanics/animal_husbandry.html` },
            { emoji: '🌾', url: `${langBase}mechanics/crops.html` },
            { emoji: '🍕', url: `${langBase}firmalife.html` },
            { emoji: '🛣️', url: `${langBase}roadsandroofs.html` },
            { emoji: '⛵', url: `${langBase}firmaciv.html` },
            { emoji: '💡', url: `${langBase}tfg_tips.html` },
          ];
          const results = await Promise.all(targets.map(t => fetchPageTitle(t.url, selectedLang).then(r => ({...t, title: r.title, url: r.url})).catch(() => ({...t, title: null}))))
          const options = results.map(r => {
            const labelText = r.title ? `${r.emoji} ${r.title}` : `${r.emoji} ${r.url}`;
            return { label: labelText.slice(0, 100), value: r.url };
          });
          const select = new StringSelectMenuBuilder()
            .setCustomId('fgtop-select')
            .setPlaceholder('Select a link')
            .addOptions(options);
          const row = new ActionRowBuilder().addComponents(select);
          return interaction.editReply({ content: 'Top links:', components: [row] });
        } catch (e) {
          DEV_MODE && console.error('[FieldGuideBot] fgtop error:', e);
          try { return interaction.editReply({ content: 'Failed to show top links.' }); } catch {}
        }
      }

      // `/fgsearch`: search the guide for pages and sections matching query keywords. Like a browser.
      if (interaction.commandName === 'fgsearch') {
        const query = interaction.options.getString('query', true);
        const langOpt = interaction.options.getString('language') || DEFAULT_LANG;
        const selectedLang = LANGS.includes(langOpt) ? langOpt : DEFAULT_LANG;
        await interaction.reply({ content: `Searching for "${query}"...`, flags: 64 });
        try {
          // Prefer JSON index search.
          let results = await searchGuideFast(query, { selectedLang, limit: 250 });
          DEV_MODE && console.log(`[FieldGuideBot] fgsearch (fast) query="${query}" results=${results.length}`);
          if (!results.length) return interaction.editReply({ content: `No results for "${query}".` });

          // If more than 25, enable paging via Prev/Next buttons
          const totalPages = Math.ceil(results.length / 25) || 1;
          const token = crypto.randomUUID ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2,8)}`;
          searchSessions.set(token, { results, query, expiresAt: Date.now() + (15 * 60 * 1000) });
          const page = 1;
          const options = buildSearchOptions(results, page);
          const placeholder = `Select a result (Page ${page}/${totalPages})`;
          const rows = buildSearchComponents(token, page, totalPages, options, placeholder);
          const note = results.length > 25 ? `Showing ${Math.min(25, results.length)} of ${results.length}` : '';
          return interaction.editReply({ content: `Results for "${query}": ${note}`, components: rows });
        } catch (e) {
          DEV_MODE && console.error('[FieldGuideBot] fgsearch error:', e);
          try { return interaction.editReply({ content: 'Failed to search/fetch.' }); } catch {}
        }
      }

      // `/fgscare`: sends GIF then posts embed.
      if (interaction.commandName === 'fgscare') {
        const gifUrl = 'https://cdn.discordapp.com/attachments/1167131539046400010/1434364792507731988/newplayer.gif?ex=695486cf&is=6953354f&hm=a244ca5b649b934ae29513698012797f070c232bc9a9242aa8c215e13fd16e94&';
        const guideUrl = new URL(`${DEFAULT_LANG}/`, BASE).toString();
        const text = `We have an [online field guide](${guideUrl})! You can use the following commands to find answers to most of your questions:\n\n- \`/fgsearch\` Browses field guide entries for your keywords.\n- \`/fgpath\` Use a specific url path to find entries (eg. \"mechanics/animal_husbandry\").\n- \`/fgtop\` Displays a list of the most useful field guide entries.\n- \`/fgscare\` Make others read too.`;
        try {
          await interaction.reply({ content: gifUrl });
          const embed = new EmbedBuilder().setDescription(text);
          return interaction.followUp({ embeds: [embed] });
        } catch (e) {
          DEV_MODE && console.error('[FieldGuideBot] fgscare error:', e);
          try { return interaction.reply({ content: 'Failed to post message.', flags: 64 }); } catch {}
        };
      };
    } catch (e) {
      DEV_MODE && console.error('[FieldGuideBot] Top-level handler error:', e);
      try { return interaction.reply({ content: 'Failed to fetch that page.', flags: 64 }); } catch {}
    };
  });

  // Allow for sharing links with other server members with a button.
  client.on('interactionCreate', async (interaction) => {
    try {
      if (interaction.isStringSelectMenu?.() && interaction.customId === 'fgsearch-select') {
        const rem = checkAndTouch(interaction.user?.id, `sel:${interaction.customId}`);
        if (rem > 0) {
          const wait = Math.ceil(rem / 1000);
          try { return interaction.reply({ content: `Please wait ${wait}s before selecting again.`, flags: 64 }); } catch {}
          return;
        };
        const rel = interaction.values?.[0];
        if (!rel) return interaction.update({ content: 'No selection received.', components: [] });
        const url = rel.startsWith('http') ? rel : `${BASE}${rel}`;
        try {
          const langPattern = LANGS.join('|');
          const match = url.match(new RegExp(`Field-Guide(?:-Modern)?/(${langPattern})/`));
          const selectedLang = match ? match[1] : DEFAULT_LANG;
          const embed = await fetchGuideEmbed(url, selectedLang);
          const shareRow = new ActionRowBuilder().addComponents(
            new ButtonBuilder().setCustomId('fg-share').setLabel('Share link').setStyle(ButtonStyle.Primary)
          );
          return interaction.update({ content: 'Result:', embeds: [embed], components: [shareRow] });
        } catch (e) {
          DEV_MODE && console.error('[FieldGuideBot] fgsearch-select fetch error:', e);
          return interaction.update({ content: 'Failed to fetch the selected page.', components: [] });
        }
      }
    } catch (e) {
      DEV_MODE && console.error('[FieldGuideBot] fgsearch-select handler error:', e);
    }
  });

  // Paging buttons for `/fgsearch` results.
  client.on('interactionCreate', async (interaction) => {
    try {
      if (!interaction.isButton?.()) return;
      const cid = interaction.customId || '';
      if (!cid.startsWith('fgsearch-prev:') && !cid.startsWith('fgsearch-next:')) return;
      const rem = checkAndTouch(interaction.user?.id, `btn:${cid.split(':')[0]}`);
      if (rem > 0) {
        const wait = Math.ceil(rem / 1000);
        try { return interaction.reply({ content: `Please wait ${wait}s before paging again.`, flags: 64 }); } catch {}
        return;
      };
      const parts = cid.split(':');
      const action = parts[0];
      const token = parts[1];
      const pageNum = parseInt(parts[2], 10) || 1;
      const session = searchSessions.get(token);
      if (!session) return interaction.reply({ content: 'This search session expired.', flags: 64 });
      if (session.expiresAt < Date.now()) {
        searchSessions.delete(token);
        return interaction.reply({ content: 'This search session expired.', flags: 64 });
      }
      const totalPages = Math.ceil(session.results.length / 25) || 1;
      let nextPage = pageNum;
      if (action === 'fgsearch-prev') nextPage = Math.max(1, pageNum - 1);
      if (action === 'fgsearch-next') nextPage = Math.min(totalPages, pageNum + 1);
      const options = buildSearchOptions(session.results, nextPage);
      const placeholder = `Select a result (Page ${nextPage}/${totalPages})`;
      const rows = buildSearchComponents(token, nextPage, totalPages, options, placeholder);
      return interaction.update({ components: rows });
    } catch (e) {
      DEV_MODE && console.error('[FieldGuideBot] fgsearch paging error:', e);
      try { await interaction.reply({ content: 'Failed to update page.', flags: 64 }); } catch {}
    }
  });

  // Share button: Posts the current embed to the channel.
  client.on('interactionCreate', async (interaction) => {
    try {
      if (!interaction.isButton?.()) return;
      if (interaction.customId !== 'fg-share') return;
      const rem = checkAndTouch(interaction.user?.id, `btn:${interaction.customId}`);
      if (rem > 0) {
        const wait = Math.ceil(rem / 1000);
        try { return interaction.reply({ content: `Please wait ${wait}s before sharing again.`, flags: 64 }); } catch {}
        return;
      };
      const msg = interaction.message;
      const srcEmbed = msg?.embeds?.[0];
      if (!srcEmbed) {
        try { return await interaction.reply({ content: 'No embed to share.', flags: 64 }); } catch {}
        return;
      }
      await interaction.channel.send({ embeds: [srcEmbed] });
      try { await interaction.reply({ content: 'Shared link to channel.', flags: 64 }); } catch {}
    } catch (e) {
      DEV_MODE && console.error('[FieldGuideBot] fg-share error:', e);
      try { await interaction.reply({ content: 'Failed to share link.', flags: 64 }); } catch {}
    }
  });

  // Share button for `/fgtop`.
  client.on('interactionCreate', async (interaction) => {
    try {
      if (!interaction.isStringSelectMenu?.()) return;
      if (interaction.customId !== 'fgtop-select') return;
      const rem = checkAndTouch(interaction.user?.id, `sel:${interaction.customId}`);
      if (rem > 0) {
        const wait = Math.ceil(rem / 1000);
        try { return interaction.reply({ content: `Please wait ${wait}s before selecting again.`, flags: 64 }); } catch {}
        return;
      };
      const sel = interaction.values?.[0];
      if (!sel) return interaction.update({ content: 'No selection received.', components: [] });
      try {
        const langPattern = LANGS.join('|');
        const match = sel.match(new RegExp(`Field-Guide(?:-Modern)?/(${langPattern})/`));
        const selectedLang = match ? match[1] : DEFAULT_LANG;
        const embed = await fetchGuideEmbed(sel, selectedLang);
        const shareRow = new ActionRowBuilder().addComponents(
          new ButtonBuilder().setCustomId('fg-share').setLabel('Share link').setStyle(ButtonStyle.Primary)
        );
        return interaction.update({ content: 'Selected:', embeds: [embed], components: [shareRow] });
      } catch (e) {
        DEV_MODE && console.error('[FieldGuideBot] fgtop-select fetch error:', e);
        return interaction.update({ content: 'Failed to fetch the selected page.', components: [] });
      }
    } catch (e) {
      DEV_MODE && console.error('[FieldGuideBot] fgtop-select handler error:', e);
    }
  });

    const token = process.env.DISCORD_TOKEN;
    if (!token) {
      DEV_MODE && console.error('Missing DISCORD_TOKEN in environment. Create a .env file.');
      process.exit(1);
    }
    client.login(token);
  }

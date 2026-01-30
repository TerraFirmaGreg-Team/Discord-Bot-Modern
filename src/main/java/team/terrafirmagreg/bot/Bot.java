package team.terrafirmagreg.bot;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Bot extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    // set to true to enable terminal logging and guild based command registration.
    public static final boolean DEV_MODE = false;

    private static final Map<String, SearchSession> searchSessions = new ConcurrentHashMap<>();
    // Rate limiting per user per user.
    private static final long RATE_LIMIT_MS;
    private static final Map<String, Long> rateLimitMap = new ConcurrentHashMap<>();

    // Fragments containing these substrings will be ignored.
    private static final List<String> FRAGMENT_BLACKLIST_SUBSTRINGS = List.of(
            "glb-viewer", "nav-primary", "navbar-content", "lang-dropdown-button",
            "bd-theme", "bd-theme-text"
    );

    static {
        String rateLimitEnv = System.getenv("RATE_LIMIT_MS");
        RATE_LIMIT_MS = rateLimitEnv != null ? Long.parseLong(rateLimitEnv) : 3000L;
    }

    private static long checkAndTouch(String userId, String key) {
        if (userId == null || key == null) return 0;
        long now = System.currentTimeMillis();
        String k = userId + ":" + key;
        Long last = rateLimitMap.getOrDefault(k, 0L);
        long diff = now - last;
        if (diff < RATE_LIMIT_MS) return RATE_LIMIT_MS - diff;
        rateLimitMap.put(k, now);
        return 0;
    }

    /**
     * Builds select menu options for a page of results (25 max).
     * @param results All search results.
     * @param page Page index.
     * @return Options for the select menu.
     */
    private static List<SelectOption> buildSearchOptions(List<Scraper.SearchResult> results, int page) {
        int start = (Math.max(1, page) - 1) * 25;
        List<Scraper.SearchResult> slice = results.subList(start, Math.min(start + 25, results.size()));

        return slice.stream()
                .map(r -> {
                    String rel = r.url.startsWith(Scraper.BASE) ? r.url.substring(Scraper.BASE.length()) : r.url;
                    if (rel.contains("#")) {
                        String frag = rel.substring(rel.lastIndexOf('#') + 1);
                        String lc = frag.toLowerCase();
                        if (FRAGMENT_BLACKLIST_SUBSTRINGS.stream().anyMatch(lc::contains)) return null;
                    }
                    if (rel.length() > 100) return null;
                    String label = (r.title != null ? r.title : "Result");
                    if (label.length() > 100) label = label.substring(0, 100);
                    String desc = rel.length() > 100 ? rel.substring(0, 100) : rel;
                    return SelectOption.of(label, rel).withDescription(desc);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Select menu for current page and Prev/Next buttons.
     * @param token Unique session token.
     * @param page Current page index.
     * @param totalPages Total number of pages.
     * @param options Select options for current page.
     * @param placeholder Placeholder text.
     * @return Array of component rows.
     */
    private static List<ActionRow> buildSearchComponents(String token, int page, int totalPages, List<SelectOption> options, String placeholder) {
        StringSelectMenu select = StringSelectMenu.create("fgsearch-select")
                .setPlaceholder(placeholder)
                .addOptions(options)
                .build();

        ActionRow row1 = ActionRow.of(select);
        if (totalPages <= 1) return List.of(row1);

        Button prev = Button.secondary("fgsearch-prev:" + token + ":" + page, "Prev")
                .withDisabled(page <= 1);
        Button next = Button.secondary("fgsearch-next:" + token + ":" + page, "Next")
                .withDisabled(page >= totalPages);

        ActionRow row2 = ActionRow.of(prev, next);
        return List.of(row1, row2);
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        if (DEV_MODE) {
            // Log IDs during testing.
            JDA jda = event.getJDA();
            logger.info("[Bot] Logged in as {}", jda.getSelfUser().getAsTag());
            logger.info("[Bot] Bot user id: {}", jda.getSelfUser().getId());
            String envClientId = System.getenv("DISCORD_CLIENT_ID");
            if (envClientId != null) {
                logger.info("[Bot] Env client id: {}", envClientId);
                if (!envClientId.equals(jda.getSelfUser().getId())) {
                    logger.warn("[Bot] WARNING: DISCORD_CLIENT_ID mismatch.");
                }
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        try {
            if (DEV_MODE) {
                // Log all interactions during testing.
                logger.info("[Bot] Interaction received: command={} isChatInput=true",
                        event.getName());
            }

            // ping pong ping pong ping pong.
            if (DEV_MODE && event.getName().equals("ping")) {
                event.reply("pong").setEphemeral(true).queue();
                return;
            }

            long rem = checkAndTouch(event.getUser().getId(), "cmd:" + event.getName());
            if (rem > 0) {
                long wait = (rem + 999) / 1000;
                event.reply("Please wait " + wait + "s before using /" + event.getName() + " again.")
                        .setEphemeral(true).queue();
                return;
            }

            switch (event.getName()) {
                case "guide" -> {
                    String sub = event.getSubcommandName();
                    if (sub == null) {
                        event.reply("Please specify a subcommand.").setEphemeral(true).queue();
                        return;
                    }
                    switch (sub) {
                        case "path" -> handleFgPath(event);
                        case "top" -> handleFgTop(event);
                        case "search" -> handleFgSearch(event);
                        case "scare" -> handleFgScare(event);
                        default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
                    }
                }
            }
        } catch (Exception e) {
            if (DEV_MODE) logger.error("[Bot] Top-level handler error:", e);
            try {
                event.reply("Failed to fetch that page.").setEphemeral(true).queue();
            } catch (Exception ignored) {}
        }
    }

    // `/fgpath`: fetch and display a guide page by the url path given.
    private void handleFgPath(SlashCommandInteractionEvent event) {
        String path = event.getOption("path").getAsString();
        String langOpt = event.getOption("language") != null ? event.getOption("language").getAsString() : Locales.DEFAULT_LANG;
        String selectedLang = Locales.LANGS.contains(langOpt) ? langOpt : Locales.DEFAULT_LANG;

        event.reply("Working on it...").setEphemeral(true).queue(hook -> {
            try {
                MessageEmbed embed = Scraper.fetchGuideEmbed(path, selectedLang);
                Button shareBtn = Button.primary("fg-share", "Share link");
                hook.editOriginalEmbeds(embed).setComponents(ActionRow.of(shareBtn)).queue();
            } catch (Exception e) {
                if (DEV_MODE) logger.error("[Bot] fgpath error:", e);
                try {
                    hook.editOriginal("Failed to fetch that page.").queue();
                } catch (Exception ignored) {}
            }
        });
    }

    // `/fgtop`: present a selector for the most important field guide links for quick access.
    private void handleFgTop(SlashCommandInteractionEvent event) {
        String langOpt = event.getOption("language") != null ? event.getOption("language").getAsString() : Locales.DEFAULT_LANG;
        String selectedLang = Locales.LANGS.contains(langOpt) ? langOpt : Locales.DEFAULT_LANG;

        event.reply("Choose a link…").setEphemeral(true).queue(hook -> {
            try {
                String langBase = Scraper.BASE + selectedLang + "/";
                List<TopTarget> targets = List.of(
                        new TopTarget("📙", langBase),
                        new TopTarget("🖥️", "https://guide.appliedenergistics.org/1.20.1/"),
                        new TopTarget("⛏️", langBase + "tfg_ores.html"),
                        new TopTarget("🌎", langBase + "the_world/geology.html"),
                        new TopTarget("🐖", langBase + "mechanics/animal_husbandry.html"),
                        new TopTarget("🌾", langBase + "mechanics/crops.html"),
                        new TopTarget("🍕", langBase + "firmalife.html"),
                        new TopTarget("🛣️", langBase + "roadsandroofs.html"),
                        new TopTarget("⛵", langBase + "firmaciv.html"),
                        new TopTarget("💡", langBase + "tfg_tips.html")
                );

                List<SelectOption> options = new ArrayList<>();
                for (TopTarget t : targets) {
                    try {
                        Scraper.SearchResult result = Scraper.fetchPageTitle(t.url, selectedLang);
                        String labelText = result.title != null ? t.emoji + " " + result.title : t.emoji + " " + result.url;
                        if (labelText.length() > 100) labelText = labelText.substring(0, 100);
                        options.add(SelectOption.of(labelText, result.url));
                    } catch (Exception e) {
                        String labelText = t.emoji + " " + t.url;
                        if (labelText.length() > 100) labelText = labelText.substring(0, 100);
                        options.add(SelectOption.of(labelText, t.url));
                    }
                }

                StringSelectMenu select = StringSelectMenu.create("fgtop-select")
                        .setPlaceholder("Select a link")
                        .addOptions(options)
                        .build();

                hook.editOriginal("Top links:")
                        .setComponents(ActionRow.of(select))
                        .queue();
            } catch (Exception e) {
                if (DEV_MODE) logger.error("[Bot] fgtop error:", e);
                try {
                    hook.editOriginal("Failed to show top links.").queue();
                } catch (Exception ignored) {}
            }
        });
    }

    // `/fgsearch`: search the guide for pages and sections matching query keywords. Like a browser.
    private void handleFgSearch(SlashCommandInteractionEvent event) {
        String query = event.getOption("query").getAsString();
        String langOpt = event.getOption("language") != null ? event.getOption("language").getAsString() : Locales.DEFAULT_LANG;
        String selectedLang = Locales.LANGS.contains(langOpt) ? langOpt : Locales.DEFAULT_LANG;

        event.reply("Searching for \"" + query + "\"...").setEphemeral(true).queue(hook -> {
            try {
                // Prefer JSON index search.
                List<Scraper.SearchResult> results = Scraper.searchGuideFast(query, selectedLang, 250);
                if (DEV_MODE) logger.info("[Bot] fgsearch (fast) query=\"{}\" results={}", query, results.size());

                if (results.isEmpty()) {
                    hook.editOriginal("No results for \"" + query + "\".").queue();
                    return;
                }

                // If more than 25, enable paging via Prev/Next buttons
                int totalPages = (int) Math.ceil(results.size() / 25.0);
                if (totalPages == 0) totalPages = 1;
                String token = UUID.randomUUID().toString();
                searchSessions.put(token, new SearchSession(results, query, System.currentTimeMillis() + (15 * 60 * 1000)));

                int page = 1;
                List<SelectOption> options = buildSearchOptions(results, page);
                String placeholder = "Select a result (Page " + page + "/" + totalPages + ")";
                List<ActionRow> rows = buildSearchComponents(token, page, totalPages, options, placeholder);
                String note = results.size() > 25 ? "Showing " + Math.min(25, results.size()) + " of " + results.size() : "";

                hook.editOriginal("Results for \"" + query + "\": " + note)
                        .setComponents(rows)
                        .queue();
            } catch (Exception e) {
                if (DEV_MODE) logger.error("[Bot] fgsearch error:", e);
                try {
                    hook.editOriginal("Failed to search/fetch.").queue();
                } catch (Exception ignored) {}
            }
        });
    }

    // `/fgscare`: sends GIF then posts embed.
    private void handleFgScare(SlashCommandInteractionEvent event) {
        String gifUrl = "https://cdn.discordapp.com/attachments/1167131539046400010/1434364792507731988/newplayer.gif?ex=695486cf&is=6953354f&hm=a244ca5b649b934ae29513698012797f070c232bc9a9242aa8c215e13fd16e94&";
        String guideUrl = Scraper.BASE + Locales.DEFAULT_LANG + "/";
        String text = "We have an [online field guide](" + guideUrl + ")! You can use the following commands to find answers to most of your questions:\n\n" +
                "- `/guide search` Browses field guide entries for your keywords.\n" +
                "- `/guide path` Use a specific url path to find entries (eg. \"mechanics/animal_husbandry\").\n" +
                "- `/guide top` Displays a list of the most useful field guide entries.\n" +
                "- `/guide scare` Make others read too.";

        try {
            event.reply(gifUrl).queue(hook -> {
                MessageEmbed embed = new EmbedBuilder().setDescription(text).build();
                event.getHook().sendMessageEmbeds(embed).queue();
            });
        } catch (Exception e) {
            if (DEV_MODE) logger.error("[Bot] fgscare error:", e);
            try {
                event.reply("Failed to post message.").setEphemeral(true).queue();
            } catch (Exception ignored) {}
        }
    }

    // Allow for sharing links with other server members with a button.
    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        try {
            if (event.getComponentId().equals("fgsearch-select")) {
                handleFgSearchSelect(event);
            } else if (event.getComponentId().equals("fgtop-select")) {
                handleFgTopSelect(event);
            }
        } catch (Exception e) {
            if (DEV_MODE) logger.error("[Bot] select handler error:", e);
        }
    }

    private void handleFgSearchSelect(StringSelectInteractionEvent event) {
        long rem = checkAndTouch(event.getUser().getId(), "sel:" + event.getComponentId());
        if (rem > 0) {
            long wait = (rem + 999) / 1000;
            event.reply("Please wait " + wait + "s before selecting again.").setEphemeral(true).queue();
            return;
        }

        String rel = event.getValues().isEmpty() ? null : event.getValues().get(0);
        if (rel == null || rel.isEmpty()) {
            event.editMessage("No selection received.").setComponents().queue();
            return;
        }

        String url = rel.startsWith("http") ? rel : Scraper.BASE + rel;
        String langPattern = String.join("|", Locales.LANGS);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Field-Guide(?:-Modern)?/(" + langPattern + ")/");
        java.util.regex.Matcher matcher = pattern.matcher(url);
        String selectedLang = matcher.find() ? matcher.group(1) : Locales.DEFAULT_LANG;

        event.deferEdit().queue(hook -> {
            try {
                MessageEmbed embed = Scraper.fetchGuideEmbed(url, selectedLang);
                Button shareBtn = Button.primary("fg-share", "Share link");
                hook.editOriginal("Result:")
                        .setEmbeds(embed)
                        .setComponents(ActionRow.of(shareBtn))
                        .queue();
            } catch (Exception e) {
                if (DEV_MODE) logger.error("[Bot] fgsearch-select fetch error:", e);
                hook.editOriginal("Failed to fetch the selected page.").setComponents().queue();
            }
        }, error -> {
            if (DEV_MODE) logger.error("[Bot] fgsearch-select defer error:", error);
        });
    }

    // Share button for `/fgtop`.
    private void handleFgTopSelect(StringSelectInteractionEvent event) {
        long rem = checkAndTouch(event.getUser().getId(), "sel:" + event.getComponentId());
        if (rem > 0) {
            long wait = (rem + 999) / 1000;
            event.reply("Please wait " + wait + "s before selecting again.").setEphemeral(true).queue();
            return;
        }

        String sel = event.getValues().isEmpty() ? null : event.getValues().get(0);
        if (sel == null || sel.isEmpty()) {
            event.editMessage("No selection received.").setComponents().queue();
            return;
        }

        try {
            String langPattern = String.join("|", Locales.LANGS);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Field-Guide(?:-Modern)?/(" + langPattern + ")/");
            java.util.regex.Matcher matcher = pattern.matcher(sel);
            String selectedLang = matcher.find() ? matcher.group(1) : Locales.DEFAULT_LANG;

            event.deferEdit().queue(hook -> {
                try {
                    MessageEmbed embed = Scraper.fetchGuideEmbed(sel, selectedLang);
                    Button shareBtn = Button.primary("fg-share", "Share link");
                    hook.editOriginal("Selected:")
                            .setEmbeds(embed)
                            .setComponents(ActionRow.of(shareBtn))
                            .queue();
                } catch (Exception e) {
                    if (DEV_MODE) logger.error("[Bot] fgtop-select fetch error:", e);
                    hook.editOriginal("Failed to fetch the selected page.").setComponents().queue();
                }
            });
        } catch (Exception e) {
            if (DEV_MODE) logger.error("[Bot] fgtop-select handler error:", e);
            event.editMessage("Failed to fetch the selected page.").setComponents().queue();
        }
    }

    // Paging buttons for `/fgsearch` results.
    // Share button: Posts the current embed to the channel.
    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        try {
            String cid = event.getComponentId();

            if (cid.startsWith("fgsearch-prev:") || cid.startsWith("fgsearch-next:")) {
                handleSearchPaging(event);
            } else if (cid.equals("fg-share")) {
                handleShareButton(event);
            }
        } catch (Exception e) {
            if (DEV_MODE) logger.error("[Bot] button handler error:", e);
        }
    }

    private void handleSearchPaging(ButtonInteractionEvent event) {
        String cid = event.getComponentId();
        long rem = checkAndTouch(event.getUser().getId(), "btn:" + cid.split(":")[0]);
        if (rem > 0) {
            long wait = (rem + 999) / 1000;
            event.reply("Please wait " + wait + "s before paging again.").setEphemeral(true).queue();
            return;
        }

        String[] parts = cid.split(":");
        String action = parts[0];
        String token = parts[1];
        int pageNum = Integer.parseInt(parts[2]);

        SearchSession session = searchSessions.get(token);
        if (session == null) {
            event.reply("This search session expired.").setEphemeral(true).queue();
            return;
        }
        if (session.expiresAt < System.currentTimeMillis()) {
            searchSessions.remove(token);
            event.reply("This search session expired.").setEphemeral(true).queue();
            return;
        }

        int totalPages = (int) Math.ceil(session.results.size() / 25.0);
        if (totalPages == 0) totalPages = 1;
        int nextPage = pageNum;
        if (action.equals("fgsearch-prev")) nextPage = Math.max(1, pageNum - 1);
        if (action.equals("fgsearch-next")) nextPage = Math.min(totalPages, pageNum + 1);

        List<SelectOption> options = buildSearchOptions(session.results, nextPage);
        String placeholder = "Select a result (Page " + nextPage + "/" + totalPages + ")";
        List<ActionRow> rows = buildSearchComponents(token, nextPage, totalPages, options, placeholder);

        event.editComponents(rows).queue();
    }

    private void handleShareButton(ButtonInteractionEvent event) {
        long rem = checkAndTouch(event.getUser().getId(), "btn:" + event.getComponentId());
        if (rem > 0) {
            long wait = (rem + 999) / 1000;
            event.reply("Please wait " + wait + "s before sharing again.").setEphemeral(true).queue();
            return;
        }

        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        if (embeds.isEmpty()) {
            event.reply("No embed to share.").setEphemeral(true).queue();
            return;
        }

        MessageEmbed srcEmbed = embeds.get(0);
        MessageChannel channel = event.getChannel();
        channel.sendMessageEmbeds(srcEmbed).queue();
        event.reply("Shared link to channel.").setEphemeral(true).queue();
    }

    // Helper classes
    private static class SearchSession {
        List<Scraper.SearchResult> results;
        String query;
        long expiresAt;

        SearchSession(List<Scraper.SearchResult> results, String query, long expiresAt) {
            this.results = results;
            this.query = query;
            this.expiresAt = expiresAt;
        }
    }

    private record TopTarget(String emoji, String url) {}

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String token = dotenv.get("DISCORD_TOKEN");
        if (token == null || token.isEmpty()) {
            token = System.getenv("DISCORD_TOKEN");
        }

        if (token == null || token.isEmpty()) {
            if (DEV_MODE) logger.error("Missing DISCORD_TOKEN in environment. Create a .env file.");
            System.exit(1);
        }

        try {
            JDA jda = JDABuilder.createLight(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .addEventListeners(new Bot())
                    .build();

            jda.awaitReady();
            logger.info("[Bot] Bot is ready!");
        } catch (Exception e) {
            logger.error("[Bot] Failed to start bot:", e);
            System.exit(1);
        }
    }
}


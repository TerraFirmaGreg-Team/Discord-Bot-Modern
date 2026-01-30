package team.terrafirmagreg.bot;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RegisterCommands {

    private static final Logger logger = LoggerFactory.getLogger(RegisterCommands.class);

    public static void main(String[] args) {
        System.out.println("RegisterCommands main method is running!");
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String clientId = dotenv.get("DISCORD_CLIENT_ID");
        if (clientId == null) clientId = System.getenv("DISCORD_CLIENT_ID");

        String token = dotenv.get("DISCORD_TOKEN");
        if (token == null) token = System.getenv("DISCORD_TOKEN");

        String guildId = dotenv.get("DISCORD_GUILD_ID");
        if (guildId == null) guildId = System.getenv("DISCORD_GUILD_ID");

        boolean devMode = Bot.DEV_MODE;
        String rawDev = dotenv.get("DEV_MODE");
        if (rawDev == null) rawDev = System.getenv("DEV_MODE");

        if (clientId == null || token == null) {
            logger.error("Missing DISCORD_CLIENT_ID or DISCORD_TOKEN in .env");
            System.exit(1);
        }

        // Build language option
        OptionData languageOption = new OptionData(OptionType.STRING, "language", "Locale (default en_us)", false);
        Locales.getLanguageChoices().forEach(choice -> languageOption.addChoice(choice.getName(), choice.getAsString()));

        List<SlashCommandData> commands = new ArrayList<>();

        // Command: guide
        SlashCommandData guideCommand = Commands.slash("guide", "Unified guide command");

        // Guide Subcommand: top
        SubcommandData topSubcommand = new SubcommandData("top", "Top Field Guide links")
                .addOptions(languageOption);

        // Guide Subcommand: search
        OptionData searchQueryOption = new OptionData(OptionType.STRING, "query", "Example: 'climate'", true);
        OptionData searchLanguageOption = new OptionData(OptionType.STRING, "language", "Locale (default en_us)", false);
        Locales.getLanguageChoices().forEach(choice -> searchLanguageOption.addChoice(choice.getName(), choice.getAsString()));
        SubcommandData searchSubcommand = new SubcommandData("search", "Search the guide!")
                .addOptions(searchQueryOption, searchLanguageOption);

        // Guide Subcommand: path
        OptionData pathOption = new OptionData(OptionType.STRING, "path", "Example: 'mechanics/animal_husbandry'", true);
        OptionData pathLanguageOption = new OptionData(OptionType.STRING, "language", "Locale (default en_us)", false);
        Locales.getLanguageChoices().forEach(choice -> pathLanguageOption.addChoice(choice.getName(), choice.getAsString()));
        SubcommandData pathSubcommand = new SubcommandData("path", "Fetch a page by URL path.")
                .addOptions(pathOption, pathLanguageOption);

        // Guide Subcommand: scare
        SubcommandData scareSubcommand = new SubcommandData("scare", "New player jump scare");

        guideCommand.addSubcommands(topSubcommand, searchSubcommand, pathSubcommand, scareSubcommand);

        commands.add(guideCommand);

        final String finalGuildId = guildId;
        final boolean finalDevMode = devMode;
        final String finalRawDev = rawDev;

        try {
            JDA jda = JDABuilder.createLight(token).build();
            jda.awaitReady();

            logger.info("[Commands] Config DEV_MODE={} raw={} guildId={}",
                    finalDevMode, finalRawDev != null ? finalRawDev : "unset", finalGuildId != null ? finalGuildId : "unset");

            CommandListUpdateAction updateAction;

            if (finalDevMode && finalGuildId != null && !finalGuildId.isEmpty()) {
                logger.info("[Commands] DEV_MODE=true: Registering GUILD slash commands for guild {}...", finalGuildId);
                updateAction = jda.getGuildById(finalGuildId).updateCommands();
            } else {
                logger.info("[Commands] Registering GLOBAL slash commands...");
                updateAction = jda.updateCommands();
            }

            updateAction.addCommands(commands).queue(
                    success -> {
                        if (finalDevMode && finalGuildId != null && !finalGuildId.isEmpty()) {
                            logger.info("[Commands] Guild registration complete (instant update).");
                        } else {
                            logger.info("[Commands] Global registration complete (may take a few minutes).");
                        }
                        jda.shutdown();
                        System.exit(0);
                    },
                    error -> {
                        logger.error("[Commands] Registration failed:", error);
                        jda.shutdown();
                        System.exit(1);
                    }
            );
        } catch (Exception e) {
            logger.error("[Commands] Registration failed:", e);
            System.exit(1);
        }
    }
}

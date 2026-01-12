package com.tfg.fieldguidebot;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RegisterCommands {

    private static final Logger logger = LoggerFactory.getLogger(RegisterCommands.class);

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String clientId = dotenv.get("DISCORD_CLIENT_ID");
        if (clientId == null) clientId = System.getenv("DISCORD_CLIENT_ID");

        String token = dotenv.get("DISCORD_TOKEN");
        if (token == null) token = System.getenv("DISCORD_TOKEN");

        String guildId = dotenv.get("DISCORD_GUILD_ID");
        if (guildId == null) guildId = System.getenv("DISCORD_GUILD_ID");

        boolean devMode = FieldGuideBot.DEV_MODE;
        String rawDev = dotenv.get("DEV_MODE");
        if (rawDev == null) rawDev = System.getenv("DEV_MODE");

        if (clientId == null || token == null) {
            logger.error("Missing DISCORD_CLIENT_ID or DISCORD_TOKEN in .env");
            System.exit(1);
        }

        // Build language option
        OptionData languageOption = new OptionData(OptionType.STRING, "language", "Locale (default en_us)", false);
        Locales.getLanguageChoices().forEach(choice -> languageOption.addChoice(choice.getName(), choice.getAsString()));

        // Field Guide Commands.
        List<SlashCommandData> commands = new ArrayList<>();

        // Ping command for testing.
        // commands.add(Commands.slash("ping", "Test"));

        commands.add(Commands.slash("fgpath", "Fetch a page by URL path.")
                .addOption(OptionType.STRING, "path", "Example: \"mechanics/animal_husbandry\"", true)
                .addOptions(languageOption));

        commands.add(Commands.slash("fgtop", "Top Field Guide links")
                .addOptions(languageOption));

        commands.add(Commands.slash("fgscare", "New player jump scare"));

        OptionData searchLanguageOption = new OptionData(OptionType.STRING, "language", "Locale (default en_us)", false);
        Locales.getLanguageChoices().forEach(choice -> searchLanguageOption.addChoice(choice.getName(), choice.getAsString()));

        commands.add(Commands.slash("fgsearch", "Search the guide!")
                .addOption(OptionType.STRING, "query", "Example: \"climate\"", true)
                .addOptions(searchLanguageOption));

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


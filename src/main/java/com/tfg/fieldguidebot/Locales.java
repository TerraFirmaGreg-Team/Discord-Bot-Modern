package com.tfg.fieldguidebot;

import net.dv8tion.jda.api.interactions.commands.Command;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Language configuration
 */
public class Locales {

    public static final String DEFAULT_LANG = "en_us";

    public static final List<String> LANGS = List.of(
            "en_us",
            "ja_jp",
            "ko_kr",
            "pt_br",
            "ru_ru",
            "uk_ua",
            "zh_cn",
            "zh_hk",
            "zh_tw"
    );

    public static final Map<String, String> LANG_LABELS = Map.of(
            "en_us", "English (en_us)",
            "ja_jp", "日本語 (ja_jp)",
            "ko_kr", "한국어 (ko_kr)",
            "pt_br", "Português (pt_br)",
            "ru_ru", "Русский (ru_ru)",
            "uk_ua", "Українська (uk_ua)",
            "zh_cn", "简体中文 (zh_cn)",
            "zh_hk", "香港繁體 (zh_hk)",
            "zh_tw", "繁體中文 (zh_tw)"
    );

    public static List<Command.Choice> getLanguageChoices() {
        return LANGS.stream()
                .map(l -> new Command.Choice(LANG_LABELS.getOrDefault(l, l), l))
                .collect(Collectors.toList());
    }
}


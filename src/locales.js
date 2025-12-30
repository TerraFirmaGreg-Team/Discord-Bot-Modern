"use strict";

// Language configuration
const DEFAULT_LANG = "en_us";
const LANGS = [
  "en_us",
  "ja_jp",
  "ko_kr",
  "pt_br",
  "ru_ru",
  "uk_ua",
  "zh_cn",
  "zh_hk",
  "zh_tw"
];

const LANG_LABELS = {
  en_us: "English (en_us)",
  ja_jp: "日本語 (ja_jp)",
  ko_kr: "한국어 (ko_kr)",
  pt_br: "Português (pt_br)",
  ru_ru: "Русский (ru_ru)",
  uk_ua: "Українська (uk_ua)",
  zh_cn: "简体中文 (zh_cn)",
  zh_hk: "香港繁體 (zh_hk)",
  zh_tw: "繁體中文 (zh_tw)"
};

function getLanguageChoices() {
  return LANGS.map(l => ({ name: LANG_LABELS[l] || l, value: l }));
}

module.exports = { DEFAULT_LANG, LANGS, getLanguageChoices, LANG_LABELS };
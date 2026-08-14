import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import type { SupportedLocale } from '../shared/types';
import enUS from './locales/en-US.json';
import zhCN from './locales/zh-CN.json';

export const supportedLocales: readonly SupportedLocale[] = ['zh-CN', 'en-US'];

const resources = {
  'zh-CN': { translation: zhCN },
  'en-US': { translation: enUS },
} as const;

function syncDocumentLanguage(language: string): void {
  if (typeof document === 'undefined') return;
  document.documentElement.lang = language === 'zh-CN' ? 'zh-CN' : 'en-US';
}

export async function initializeI18n(locale: SupportedLocale): Promise<void> {
  if (!i18n.isInitialized) {
    await i18n
      .use(initReactI18next)
      .init({
        resources,
        lng: locale,
        fallbackLng: 'en-US',
        supportedLngs: [...supportedLocales],
        interpolation: { escapeValue: false },
        returnNull: false,
      });
    i18n.on('languageChanged', syncDocumentLanguage);
  } else if (i18n.resolvedLanguage !== locale) {
    await i18n.changeLanguage(locale);
  }
  syncDocumentLanguage(locale);
}

export default i18n;

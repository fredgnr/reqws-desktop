import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import type { ResolvedGlobalSettings } from '../shared/types';
import { App } from './App';
import { initializeI18n } from './i18n';
import './styles/app.css';

export async function bootstrap(): Promise<void> {
  let settings: ResolvedGlobalSettings | null = null;
  try {
    settings = await window.reqws.settings.get();
    await initializeI18n(settings.effectiveLocale);
  } catch {
    // A bad settings file must not leave the window blank. The App retries the
    // settings request and shows a localized error while English is the safe
    // startup fallback.
    await initializeI18n('en-US');
  }

  const container = document.getElementById('root');
  if (!container) throw new Error('Renderer root element is missing.');

  createRoot(container).render(
    <StrictMode>
      <App initialSettings={settings} />
    </StrictMode>,
  );
}

void bootstrap();

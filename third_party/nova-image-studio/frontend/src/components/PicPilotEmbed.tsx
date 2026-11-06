'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { syncDynamicModelExports } from '@/lib/gemini-config';

const BOOTSTRAP_VERSION = 'picpilot-nova-registry-v2';

function installPicPilotRegistry() {
  const registry = {
    imageModels: [{
      id: 'picpilot-image',
      protocol: 'openai',
      name: 'PicPilot 生图',
      modelId: 'gpt-image-2',
      apiKey: 'picpilot-bridge',
      baseUrl: 'https://bridge.invalid',
      builtinPreset: 'gpt-image-2',
      maxRefImages: 10,
      maxOutputSize: '4K',
      supportsAdvancedParams: true,
    }],
    textModels: [{
      id: 'picpilot-text',
      protocol: 'openai-chat-completions',
      name: 'PicPilot 文案',
      modelId: 'gpt-5.5',
      apiKey: 'picpilot-bridge',
      baseUrl: 'https://bridge.invalid',
      note: '由 PicPilot 后台统一路由',
    }],
    defaults: {
      textToImage: 'picpilot-image',
      imageToImage: 'picpilot-image',
      reversePrompt: 'picpilot-text',
      agent: 'picpilot-text',
      promptOptimize: 'picpilot-text',
      imageDescribe: 'picpilot-text',
    },
  };

  window.localStorage.setItem('nova-model-registry', JSON.stringify(registry));
  for (const key of ['nova-image-generation-settings', 'nova-t2i-settings', 'nova-i2i-settings']) {
    let current: Record<string, unknown> = {};
    try {
      current = JSON.parse(window.localStorage.getItem(key) || '{}');
    } catch {
      current = {};
    }
    window.localStorage.setItem(key, JSON.stringify({ ...current, model: 'picpilot-image' }));
  }
  window.sessionStorage.setItem('picpilot:nova:bootstrap-version', BOOTSTRAP_VERSION);
  syncDynamicModelExports();
}

function loadAdapterScript(src: string) {
  if (document.querySelector(`script[src="${src}"]`)) return;
  const script = document.createElement('script');
  script.src = src;
  script.async = false;
  script.dataset.app = 'studio';
  document.head.appendChild(script);
}

export function PicPilotEmbed({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    installPicPilotRegistry();
    setReady(true);
  }, []);

  useEffect(() => {
    if (!ready) return;
    loadAdapterScript('/picpilot-canvas-bridge.js');
    loadAdapterScript('/picpilot-canvas-brand.js');
  }, [ready]);

  if (!ready) {
    return (
      <main className="grid min-h-screen place-items-center bg-background text-foreground">
        <div className="flex items-center gap-3 text-sm font-semibold text-muted-foreground">
          <span className="size-5 animate-spin rounded-full border-2 border-primary/25 border-t-primary" />
          PicPilot 正在准备创作空间
        </div>
      </main>
    );
  }

  return children;
}

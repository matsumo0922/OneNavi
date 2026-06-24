/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GOOGLE_API_KEY: string;
  readonly VITE_HERE_API_KEY: string;
  readonly VITE_HERE_APP_ID: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

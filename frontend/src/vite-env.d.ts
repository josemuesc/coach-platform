/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/react" />

interface ImportMetaEnv {
  /** Origin of the API (empty = same origin). Also the only extra origin the CSP allows in connect-src. */
  readonly VITE_API_BASE_URL?: string;
}

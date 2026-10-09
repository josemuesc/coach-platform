import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts', 'src/api/errorCatalog.generated.ts', 'playwright-report', 'test-results'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}', 'e2e/**/*.ts'],
    languageOptions: { globals: { ...globals.browser } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // ONE door to the server: the typed client in src/api (it adds the token, the error mapping and no-store caching)
      'no-restricted-globals': ['error', { name: 'fetch', message: 'Use the typed client in src/api/client.ts.' }],
      'no-restricted-syntax': [
        'error',
        { selector: "Literal[value=/^https?:\\/\\//]", message: 'No hard-coded URLs: the API origin comes from VITE_API_BASE_URL.' },
      ],
    },
  },
  {
    // the client itself, the end-to-end tests and the smoke page may talk to fixed URLs
    files: ['src/api/client.ts', 'e2e/**/*.ts', 'src/features/dev/**', 'src/**/*.test.{ts,tsx}'],
    rules: { 'no-restricted-globals': 'off', 'no-restricted-syntax': 'off' },
  },
  {
    // the two areas never import each other: an area is a separate chunk and a separate set of permissions
    files: ['src/features/coach/**'],
    rules: { 'no-restricted-imports': ['error', { patterns: ['**/features/student/**'] }] },
  },
  {
    files: ['src/features/student/**'],
    rules: { 'no-restricted-imports': ['error', { patterns: ['**/features/coach/**'] }] },
  },
  { files: ['scripts/**/*.mjs', '*.mjs', '*.js'], languageOptions: { globals: { ...globals.node } } },
);

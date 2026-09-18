import js from '@eslint/js';
import sonarjs from 'eslint-plugin-sonarjs';
import unicorn from 'eslint-plugin-unicorn';
import tseslint from 'typescript-eslint';

// Carried over from kurssit-pro's eslint.config.js (KAR-60), minus what is specific to that
// repository. Formatting is Biome's job (biome.json), not ESLint's.
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  unicorn.configs.recommended,
  sonarjs.configs.recommended,
  {
    languageOptions: {
      parserOptions: {
        // vitest.config.ts belongs to neither tsconfig; lint it under the default project.
        projectService: { allowDefaultProject: ['vitest.config.ts'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Components are PascalCase (ChatView.tsx), hooks and helpers camelCase, scripts kebab.
      'unicorn/filename-case': [
        'error',
        { cases: { camelCase: true, kebabCase: true, pascalCase: true } },
      ],
      // Biome's formatter lower-cases hexadecimal digits; follow it rather than fight it.
      'unicorn/number-literal-case': ['error', { hexadecimalValue: 'lowercase' }],
      // JSDoc one-liners (`/** text */`) are the house style.
      'unicorn/single-line-block-comment-style': 'off',
      // `null` is a value here: React refs and renders, JSON from the API.
      'unicorn/no-null': 'off',
      // Abbreviations such as props, ref, req, res, err, e are the house style.
      'unicorn/name-replacements': 'off',
      'unicorn/no-non-function-verb-prefix': 'off',
      'sonarjs/no-nested-functions': 'off',
      'unicorn/max-nested-calls': 'off',
      // Duplicates `@typescript-eslint/no-unused-vars`, which is configured with the `_` patterns.
      'sonarjs/no-unused-vars': 'off',
      'unicorn/no-await-expression-member': 'off',
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    // eslint.config.js and scripts/*.mjs are plain JS and outside the TypeScript project.
    files: ['**/*.js', '**/*.mjs'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  {
    // Node scripts, not browser code.
    files: ['scripts/**'],
    languageOptions: { globals: { console: 'readonly', process: 'readonly' } },
  },
  {
    files: ['**/*.test.ts', '**/*.test.tsx', 'scripts/**'],
    rules: {
      // Tests and scripts assert on and print real values.
      '@typescript-eslint/no-non-null-assertion': 'off',
      'sonarjs/no-duplicate-string': 'off',
      'unicorn/no-process-exit': 'off',
      'unicorn/no-top-level-assignment-in-function': 'off',
      'unicorn/require-array-sort-compare': 'off',
      'sonarjs/no-alphabetical-sort': 'off',
    },
  },
);

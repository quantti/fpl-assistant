import { expect, it } from 'vitest';
import template from '../../../docs/email-templates/reset-password.html?raw';

it('ships a recovery email with a Supabase verification button and a visible fallback link', () => {
  expect(template.match(/href="\{\{ \.ConfirmationURL \}\}"/g)).toHaveLength(2);
  expect(template).toContain('>{{ .ConfirmationURL }}</a>');
  expect(template).toContain('Reset your password');
  expect(template).not.toContain('{{ .SiteURL }}');
  expect(template).not.toContain('<script');
});

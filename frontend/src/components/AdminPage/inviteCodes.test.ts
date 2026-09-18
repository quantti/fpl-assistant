import { describe, expect, it } from 'vitest';
import {
  byNewest,
  createBody,
  placesLine,
  releaseNote,
  shortUserId,
  STATE_WORDS,
  usedText,
} from './inviteCodes';

describe('invite-code display helpers', () => {
  it('names every server state with the page wording', () => {
    expect(STATE_WORDS).toEqual({
      OPEN: 'Open',
      DISABLED: 'Disabled',
      EXHAUSTED: 'Used up',
      EXPIRED: 'Expired',
      NOT_YET_VALID: 'Not yet valid',
    });
  });

  it('shows uses over the ceiling, or over infinity without one', () => {
    expect(usedText({ uses: 7, maxUses: 100 })).toBe('7 / 100');
    expect(usedText({ uses: 7 })).toBe('7 / ∞');
  });

  it('writes the places line', () => {
    expect(placesLine({ accountCap: 30, placesTaken: 12, burnt: 1 })).toBe(
      '12 of 30 places taken · 1 burnt',
    );
  });

  it('reports a release, its reconciliation, and a grace-period hold', () => {
    expect(releaseNote({ released: 1, reconciled: ['testi'] }, 0)).toBe(
      'Released 1 place. Reconciled: testi.',
    );
    expect(releaseNote({ released: 2, reconciled: [] }, 0)).toBe('Released 2 places.');
    expect(releaseNote({ released: 0, reconciled: [] }, 1)).toBe(
      'Nothing released: the remaining burnt places are younger than the grace period and may' +
        ' still be sign-ups in progress.',
    );
    expect(releaseNote({ released: 0, reconciled: [] }, 0)).toBe('Nothing to release.');
  });

  it('shortens a user id to eight characters', () => {
    expect(shortUserId('0f3a9c12-aaaa-bbbb-cccc-000000000000')).toBe('0f3a9c12');
  });

  it('orders redemptions newest first', () => {
    const older = { id: 1, userId: null, redeemedAt: '2026-09-01T10:00:00Z' };
    const newer = { id: 2, userId: 'u', redeemedAt: '2026-09-02T10:00:00Z' };
    expect([older, newer].sort(byNewest)).toEqual([newer, older]);
  });

  it('orders fractional-second timestamps by instant, not by string, and ties by id', () => {
    const whole = { id: 1, userId: null, redeemedAt: '2026-09-01T10:00:00Z' };
    const fractional = { id: 2, userId: 'u', redeemedAt: '2026-09-01T10:00:00.5Z' };
    expect([whole, fractional].sort(byNewest)).toEqual([fractional, whole]);

    const lowerId = { id: 1, userId: null, redeemedAt: '2026-09-01T10:00:00Z' };
    const higherId = { id: 2, userId: 'u', redeemedAt: '2026-09-01T10:00:00Z' };
    expect([lowerId, higherId].sort(byNewest)).toEqual([higherId, lowerId]);
  });
});

describe('createBody', () => {
  const blank = {
    code: 'podcast',
    label: '',
    maxUses: '',
    benefitKind: 'access' as const,
    benefitValue: '',
    validFrom: '',
    validUntil: '',
    note: '',
  };

  it('sends null for an empty ceiling and empty optional text', () => {
    expect(createBody(blank)).toEqual({
      code: 'podcast',
      label: null,
      maxUses: null,
      benefitKind: 'access',
      benefitValue: null,
      validFrom: null,
      validUntil: null,
      note: null,
    });
  });

  it('sends the discount percentage only for a discount code', () => {
    expect(
      createBody({ ...blank, benefitKind: 'discount_percent', benefitValue: '20' }),
    ).toMatchObject({ benefitKind: 'discount_percent', benefitValue: 20 });
    expect(createBody({ ...blank, benefitValue: '20' })).toMatchObject({ benefitValue: null });
  });

  it('sends dates as the local start and end of the chosen days', () => {
    const body = createBody({
      ...blank,
      maxUses: '50',
      validFrom: '2026-10-01',
      validUntil: '2026-10-31',
    });
    expect(body.maxUses).toBe(50);
    expect(new Date(body.validFrom!).getTime()).toBe(new Date('2026-10-01T00:00:00').getTime());
    expect(new Date(body.validUntil!).getTime()).toBe(new Date('2026-10-31T23:59:59').getTime());
  });
});

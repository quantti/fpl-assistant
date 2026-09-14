/**
 * The invite-code admin API as the page sees it, plus the pure wording helpers the page and the
 * form share. Optional numbers are absent on the wire (the server serialises NON_NULL), so they
 * are optional here rather than nullable — "no ceiling" is a missing field, not a zero.
 */

export type CodeState = 'OPEN' | 'DISABLED' | 'EXHAUSTED' | 'NOT_YET_VALID' | 'EXPIRED'
export type BenefitKind = 'access' | 'discount_percent'

export interface InviteCodeView {
  id: number
  code: string
  label: string
  note?: string
  maxUses?: number
  uses: number
  remaining?: number
  accounts: number
  burnt: number
  lastRedeemedAt?: string
  benefitKind: BenefitKind
  benefitValue?: number
  active: boolean
  validFrom?: string
  validUntil?: string
  state: CodeState
  createdAt: string
}

export interface InviteCodeList {
  accountCap: number
  placesTaken: number
  burnt: number
  codes: InviteCodeView[]
}

/** `userId` is null for a burnt place — a sign-up that reserved the place and never finished. */
export interface Redemption {
  id: number
  userId: string | null
  redeemedAt: string
}

export interface ReleaseResult {
  released: number
  reconciled: string[]
}

export const NOT_AVAILABLE = 'This page is not available for this account.'
export const SAVE_FAILED = 'That could not be saved. Nothing was changed — try again shortly.'

/** The server's state, in the page's words. Rendered from the response, never computed here. */
export const STATE_WORDS: Record<CodeState, string> = {
  OPEN: 'Open',
  DISABLED: 'Disabled',
  EXHAUSTED: 'Used up',
  EXPIRED: 'Expired',
  NOT_YET_VALID: 'Not yet valid',
}

export function usedText(code: Pick<InviteCodeView, 'uses' | 'maxUses'>): string {
  return `${code.uses} / ${code.maxUses === undefined ? '∞' : code.maxUses}`
}

export function placesLine(list: Pick<InviteCodeList, 'accountCap' | 'placesTaken' | 'burnt'>): string {
  return `${list.placesTaken} of ${list.accountCap} places taken · ${list.burnt} burnt`
}

export function benefitText(code: Pick<InviteCodeView, 'benefitKind' | 'benefitValue'>): string {
  return code.benefitKind === 'discount_percent' ? `${code.benefitValue}% off` : 'Access'
}

/** @param burntAfter the list's burnt count after the release, re-read from the server. */
export function releaseNote(result: ReleaseResult, burntAfter: number): string {
  if (result.released === 0) {
    return burntAfter > 0
      ? 'Nothing released: the remaining burnt places are younger than the grace period and may'
        + ' still be sign-ups in progress.'
      : 'Nothing to release.'
  }
  const places = `Released ${result.released} ${result.released === 1 ? 'place' : 'places'}.`
  return result.reconciled.length === 0 ? places : `${places} Reconciled: ${result.reconciled.join(', ')}.`
}

export function shortUserId(userId: string): string {
  return userId.slice(0, 8)
}

export function byNewest(a: Redemption, b: Redemption): number {
  return b.redeemedAt.localeCompare(a.redeemedAt)
}

/** The new-code form's fields as typed — strings, so an empty field is '' and not a NaN. */
export interface NewCodeFields {
  code: string
  label: string
  maxUses: string
  benefitKind: BenefitKind
  benefitValue: string
  validFrom: string
  validUntil: string
  note: string
}

export interface CreateBody {
  code: string
  label: string | null
  maxUses: number | null
  benefitKind: BenefitKind
  benefitValue: number | null
  validFrom: string | null
  validUntil: string | null
  note: string | null
}

/**
 * Dates are the admin's local days: a code valid until the 31st is valid through the 31st where
 * the admin sits, so the end of that day is what is sent. The server takes any instant.
 */
export function createBody(fields: NewCodeFields): CreateBody {
  const text = (value: string) => (value.trim() === '' ? null : value.trim())
  return {
    code: fields.code.trim(),
    label: text(fields.label),
    maxUses: fields.maxUses === '' ? null : Number(fields.maxUses),
    benefitKind: fields.benefitKind,
    benefitValue: fields.benefitKind === 'discount_percent' && fields.benefitValue !== ''
      ? Number(fields.benefitValue)
      : null,
    validFrom: fields.validFrom === '' ? null : new Date(`${fields.validFrom}T00:00:00`).toISOString(),
    validUntil: fields.validUntil === '' ? null : new Date(`${fields.validUntil}T23:59:59`).toISOString(),
    note: text(fields.note),
  }
}

/** The `detail` of a Spring problem-details body, or null when the body is not one. */
export async function problemDetail(response: Response): Promise<string | null> {
  try {
    const body: { detail?: unknown } = await response.json()
    return typeof body.detail === 'string' ? body.detail : null
  } catch {
    return null
  }
}

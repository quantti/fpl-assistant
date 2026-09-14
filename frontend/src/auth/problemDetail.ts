/** The `detail` of a Spring problem-details body, trimmed, or null when the body is not one or is blank. */
export async function problemDetail(response: Response): Promise<string | null> {
  try {
    const body: { detail?: unknown } = await response.json()
    return typeof body.detail === 'string' ? body.detail.trim() || null : null
  } catch {
    return null
  }
}

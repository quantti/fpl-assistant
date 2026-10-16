export interface Comparison {
  asOf: string;
  horizon: string;
  sources: string[];
  rows: ComparisonRow[];
}

export interface ComparisonRow {
  season: string;
  elementId: number;
  market: {
    webName: string;
    fullName: string | null;
    teamShort: string;
    position: number;
    nowCost: number;
    status: string | null;
    news: string | null;
    ownership: number | null;
    penalties: number | null;
    directFreeKicks: number | null;
    corners: number | null;
    capturedAt: string;
  } | null;
  totals: {
    points: number | null;
    minutes: number | null;
    goals: number | null;
    assists: number | null;
    cleanSheets: number | null;
    saves: number | null;
    bonus: number | null;
    xg: number | null;
    xa: number | null;
    xgi: number | null;
    source: string;
  } | null;
  pointsPerMillion: number | null;
  reason: string | null;
  forecast: {
    expectedCleanSheets: number | null;
    reason: string | null;
    weeks: {
      gameweek: number;
      fixtures: {
        id: number;
        opponent: string;
        home: boolean;
        difficulty: number | null;
        cleanSheetProbability: number | null;
      }[];
    }[];
  };
  history: {
    id: number;
    gameweek: number | null;
    opponent: string;
    home: boolean;
    kickoff: string;
    minutes: number | null;
    points: number | null;
    goals: number | null;
    assists: number | null;
    xg: number | null;
    xa: number | null;
    xgi: number | null;
    defcon: number | null;
    evidence: string;
  }[];
  historyReason: string | null;
  defcon: {
    threshold: number;
    hits: number;
    readings: number;
    actionsPer90: number | null;
    observedAppearances: number;
  } | null;
  observationSources: string[];
}

export const numberText = (value: number | null | undefined, decimals = 0) =>
  value == null ? 'n/a' : value.toFixed(decimals);

export function availability(status: string | null | undefined) {
  const labels: Record<string, string> = {
    a: 'Available',
    d: 'Doubtful',
    i: 'Injured',
    s: 'Suspended',
    u: 'Unavailable',
    n: 'Not available',
  };
  return status ? (labels[status] ?? 'Unknown') : 'Unknown';
}

export function compareNumbers(a: number | null | undefined, b: number | null | undefined) {
  if (a == null) return b == null ? 0 : 1;
  return b == null ? -1 : b - a;
}

export type MetricSort = 'price' | 'points' | 'value' | 'xg' | 'xa' | 'xgi' | 'cs';
export function sortMetric(row: ComparisonRow | undefined, sort: MetricSort) {
  if (!row) return null;
  switch (sort) {
    case 'price': {
      return row.market?.nowCost;
    }
    case 'points': {
      return row.totals?.points;
    }
    case 'value': {
      return row.pointsPerMillion;
    }
    case 'cs': {
      return row.forecast.expectedCleanSheets;
    }
    default: {
      return row.totals?.[sort];
    }
  }
}

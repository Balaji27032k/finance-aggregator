export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export type Direction = 'DEBIT' | 'CREDIT';

export interface Source { type: string; bankCode: string | null }

export interface Txn {
  groupId: number;
  direction: Direction;
  amount: number;
  currency: string;
  transactionDate: string | null;
  eventTimestamp: string;
  counterparty: string | null;
  counterpartyAccountLast4: string | null;
  accountLast4: string | null;
  paymentMode: string | null;
  referenceNo: string | null;
  balanceAfter: number | null;
  note: string | null;
  category: string;
  label: string | null;
  sources: Source[];
  splits: { category: string; name: string | null; amount: number }[];
}

export interface AskTotals { total: number; count: number }
export interface AskGroup { name: string; total: number; count: number }
export interface AskItem { name: string; title: string; date: string; amount: number; related: boolean }
export interface AskTurn { role: 'user' | 'assistant'; text: string }

export interface AskReply {
  answer: string;
  aiUsed: boolean;
  intent: string;
  periodLabel: string;
  from: string;
  to: string;
  terms: string[];
  exact: AskTotals | null;
  related: AskTotals | null;
  breakdownTitle: string | null;
  breakdown: AskGroup[];
  items: AskItem[];
  suggestions: string[];
  sentToAi: string[];
  note: string | null;
}

export interface SpendLookup {
  term: string;
  from: string;
  to: string;
  total: number;
  count: number;
  items: { transaction: Txn; matched: number }[];
}

export interface Totals { income: number; spend: number; net: number; count: number; avgDailySpend: number; largestExpense: number }
export interface DayPoint { date: string; income: number; spend: number }
export interface MonthPoint { month: string; income: number; spend: number }
export interface Bucket { key: string; total: number; count: number }
export interface Recurring { name: string; typicalAmount: number; occurrences: number; lastDate: string; nextExpected: string }
export interface AccountBalance { bankCode: string; accountLast4: string; balance: number; asOf: string }
export interface Projection { month: string; spentSoFar: number; projected: number; daysElapsed: number; daysInMonth: number; dailyRate: number }

export interface Summary {
  range: { from: string; to: string; days: number };
  totals: Totals;
  previous: Totals;
  savingsRatePercent: number | null;
  daily: DayPoint[];
  monthly: MonthPoint[];
  topPayees: Bucket[];
  topIncomeSources: Bucket[];
  byCategory: Bucket[];
  byMode: Bucket[];
  byBank: Bucket[];
  byWeekday: Bucket[];
  byHour: Bucket[];
  largestExpenses: Txn[];
  recurring: Recurring[];
  spikes: DayPoint[];
  balances: AccountBalance[];
  projection: Projection;
}

export interface TxnPage {
  total: number;
  page: number;
  size: number;
  totalDebit: number;
  totalCredit: number;
  categories: string[];
  items: Txn[];
}

export interface Health {
  transactions: number;
  multiChannelTransactions: number;
  firstEvent: string | null;
  bySource: { key: string; count: number; latest: string | null }[];
  byBank: { key: string; count: number; latest: string | null }[];
  serverTime: string;
}

export interface TxnQuery {
  from: string;
  to: string;
  direction?: Direction | '';
  category?: string;
  source?: string;
  q?: string;
  minAmount?: string;
  maxAmount?: string;
  sort?: 'date' | 'amount';
  order?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener = () => {};
export function setUnauthorizedListener(listener: UnauthorizedListener) {
  onUnauthorized = listener;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { credentials: 'same-origin', ...init });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. It may be waking up; try again in a minute.');
  }
  if (response.status === 401 && !path.endsWith('/login')) {
    onUnauthorized();
  }
  if (!response.ok) {
    let message = 'Something went wrong';
    try {
      const body = await response.json();
      if (typeof body?.error === 'string') message = body.error;
    } catch {
      /* no body */
    }
    throw new ApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  return search.toString();
}

export const api = {
  me: () => request<{ username: string }>('/api/admin/me'),
  login: (username: string, password: string) =>
    request<{ username: string }>('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<void>('/api/admin/logout', { method: 'POST' }),
  summary: (from: string, to: string, previous?: { from: string; to: string }) =>
    request<Summary>(`/api/admin/summary?${query({ from, to, previousFrom: previous?.from, previousTo: previous?.to })}`),
  transactions: (q: TxnQuery) => request<TxnPage>(`/api/admin/transactions?${query({ ...q })}`),
  health: () => request<Health>('/api/admin/health'),
  spend: (term: string, from: string, to: string) => request<SpendLookup>(`/api/admin/spend?${query({ term, from, to })}`),
  ask: (question: string, history: AskTurn[]) =>
    request<AskReply>('/api/admin/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, history }),
    }),
};

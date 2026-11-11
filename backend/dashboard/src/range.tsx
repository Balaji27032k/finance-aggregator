import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { toIso } from './format';

export type PresetId = '7d' | '30d' | 'month' | 'lastmonth' | '90d' | '180d' | '365d' | 'custom';

export interface DateRange { from: string; to: string; preset: PresetId }

export const PRESETS: { id: PresetId; label: string }[] = [
  { id: '7d', label: '7D' },
  { id: '30d', label: '30D' },
  { id: 'month', label: 'This month' },
  { id: 'lastmonth', label: 'Last month' },
  { id: '90d', label: '3M' },
  { id: '180d', label: '6M' },
  { id: '365d', label: '1Y' },
];

export function rangeFor(preset: PresetId, now = new Date()): { from: string; to: string } {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const daysBack = (n: number) => new Date(today.getFullYear(), today.getMonth(), today.getDate() - n);
  switch (preset) {
    case '7d': return { from: toIso(daysBack(6)), to: toIso(today) };
    case '90d': return { from: toIso(daysBack(89)), to: toIso(today) };
    case '180d': return { from: toIso(daysBack(179)), to: toIso(today) };
    case '365d': return { from: toIso(daysBack(364)), to: toIso(today) };
    case 'month': return { from: toIso(new Date(today.getFullYear(), today.getMonth(), 1)), to: toIso(today) };
    case 'lastmonth': {
      return {
        from: toIso(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
        to: toIso(new Date(today.getFullYear(), today.getMonth(), 0)),
      };
    }
    default: return { from: toIso(daysBack(29)), to: toIso(today) };
  }
}

interface Ctx { range: DateRange; setPreset: (p: PresetId) => void; setCustom: (from: string, to: string) => void }
const RangeContext = createContext<Ctx | null>(null);

const KEY = 'finance.range';

function initial(): DateRange {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null') as { preset: PresetId; from?: string; to?: string } | null;
    if (saved?.preset === 'custom' && saved.from && saved.to) return { preset: 'custom', from: saved.from, to: saved.to };
    if (saved && saved.preset !== 'custom') return { preset: saved.preset, ...rangeFor(saved.preset) };
  } catch {
    /* ignore */
  }
  return { preset: '30d', ...rangeFor('30d') };
}

export function RangeProvider({ children }: { children: ReactNode }) {
  const [range, setRange] = useState<DateRange>(initial);
  const value = useMemo<Ctx>(() => ({
    range,
    setPreset: (preset) => {
      const next = { preset, ...rangeFor(preset) };
      localStorage.setItem(KEY, JSON.stringify({ preset }));
      setRange(next);
    },
    setCustom: (from, to) => {
      if (!from || !to || to < from) return;
      localStorage.setItem(KEY, JSON.stringify({ preset: 'custom', from, to }));
      setRange({ preset: 'custom', from, to });
    },
  }), [range]);
  return <RangeContext.Provider value={value}>{children}</RangeContext.Provider>;
}

export function useRange(): Ctx {
  const ctx = useContext(RangeContext);
  if (!ctx) throw new Error('RangeProvider missing');
  return ctx;
}

export function RangePicker() {
  const { range, setPreset, setCustom } = useRange();
  return (
    <div className="range">
      <div className="seg" role="group" aria-label="Date range">
        {PRESETS.map((p) => (
          <button key={p.id} className={range.preset === p.id ? 'on' : ''} onClick={() => setPreset(p.id)}>{p.label}</button>
        ))}
      </div>
      <input className="field" type="date" aria-label="From" value={range.from} max={range.to}
        onChange={(e) => setCustom(e.target.value, range.to)} />
      <input className="field" type="date" aria-label="To" value={range.to} min={range.from}
        onChange={(e) => setCustom(range.from, e.target.value)} />
    </div>
  );
}

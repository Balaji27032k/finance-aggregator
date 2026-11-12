import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ArrowDownRight, ArrowUpRight, Minus, X } from 'lucide-react';
import { categoryStyle } from './categories';
import { cleanName, dateTimeIst, inr, longDate, sourceLabel } from './format';
import type { Txn } from './api';

export function useAsync<T>(load: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  useEffect(() => {
    let cancelled = false;
    setState((s) => ({ data: s.data, loading: true }));
    load()
      .then((data) => !cancelled && setState({ data, loading: false }))
      .catch((e: Error) => !cancelled && setState({ error: e.message, loading: false }));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

/** A rupee amount that counts up to its value, unless the system asks for reduced motion. */
export function Money({ value }: { value: number }) {
  const [shown, setShown] = useState(value);
  const from = useRef(value);
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      from.current = value;
      setShown(value);
      return;
    }
    const begin = from.current;
    const start = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / 600);
      const v = begin + (value - begin) * (1 - Math.pow(1 - t, 3));
      from.current = v;
      setShown(v);
      if (t < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return <>{inr(shown, true)}</>;
}

export function Card({ title, hint, children, className = '' }: { title?: ReactNode; hint?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {title && (
        <h2>
          <span>{title}</span>
          {hint && <span className="hint">{hint}</span>}
        </h2>
      )}
      {children}
    </section>
  );
}

export function Skeleton({ height = 160 }: { height?: number }) {
  return <div className="skeleton" style={{ minHeight: height }} />;
}

export function ErrorBox({ message }: { message: string }) {
  return <div className="error">{message}</div>;
}

export function Delta({ value, goodWhenUp }: { value: number | null; goodWhenUp: boolean }) {
  if (value === null) return <span className="pill flat"><Minus size={12} /> new</span>;
  const rounded = Math.round(value);
  if (rounded === 0) return <span className="pill flat"><Minus size={12} /> 0%</span>;
  const up = rounded > 0;
  const good = up === goodWhenUp;
  return (
    <span className={`pill ${good ? 'good' : 'bad'}`}>
      {up ? <ArrowUpRight size={12} /> : <ArrowDownRight size={12} />}
      {Math.abs(rounded)}%
    </span>
  );
}

export function CategoryAvatar({ category, size = 38 }: { category: string; size?: number }) {
  const { color, icon: Icon } = categoryStyle(category);
  return (
    <span className="avatar" style={{ width: size, height: size, background: `color-mix(in srgb, ${color} 18%, transparent)`, color }}>
      <Icon size={Math.round(size * 0.5)} />
    </span>
  );
}

export function CategoryBadge({ category }: { category: string }) {
  const { color } = categoryStyle(category);
  return (
    <span className="badge" style={{ background: `color-mix(in srgb, ${color} 16%, transparent)`, color }}>
      {category}
    </span>
  );
}

export function SourceChips({ txn }: { txn: Txn }) {
  const types = Array.from(new Set(txn.sources.map((s) => s.type)));
  return (
    <span style={{ display: 'inline-flex', gap: 4 }}>
      {types.map((t) => (
        <span className="chip" key={t}>{sourceLabel(t)}</span>
      ))}
    </span>
  );
}

export function TxnRow({ txn, onOpen }: { txn: Txn; onOpen: (t: Txn) => void }) {
  const credit = txn.direction === 'CREDIT';
  return (
    <div className="row" role="button" tabIndex={0} style={{ cursor: 'pointer' }}
      onClick={() => onOpen(txn)} onKeyDown={(e) => e.key === 'Enter' && onOpen(txn)}>
      <CategoryAvatar category={txn.category} />
      <div className="grow">
        <div className="title">{cleanName(txn.counterparty)}</div>
        <div className="sub">{txn.label ? `\u201c${txn.label}\u201d \u00b7 ` : ''}{txn.splits.length > 0 ? `Split: ${txn.splits.map((p) => p.name ?? p.category).join(', ')}` : txn.category} · {txn.transactionDate ? longDate(txn.transactionDate) : dateTimeIst(txn.eventTimestamp)}</div>
      </div>
      <div className={`num ${credit ? 'income' : ''}`} style={{ fontWeight: 600 }}>
        {credit ? '+' : '-'}{inr(txn.amount)}
      </div>
    </div>
  );
}

export function TxnDrawer({ txn, onClose }: { txn: Txn | null; onClose: () => void }) {
  useEffect(() => {
    if (!txn) return;
    const handler = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [txn, onClose]);
  if (!txn) return null;
  const credit = txn.direction === 'CREDIT';
  const rows: [string, ReactNode][] = [
    ['Category', txn.splits.length > 0 ? 'Split across categories' : <CategoryBadge key="c" category={txn.category} />],
    ...txn.splits.map((p): [string, ReactNode] => ['\u00a0\u00a0' + (p.name ?? p.category), inr(p.amount) + (p.name ? ` (${p.category})` : '')]),
    ['What for', txn.label ?? '-'],
    ['Date', txn.transactionDate ? longDate(txn.transactionDate) : '-'],
    ['Received', dateTimeIst(txn.eventTimestamp) + ' IST'],
    ['Payment mode', txn.paymentMode ?? '-'],
    ['Account', txn.accountLast4 ? `ending ${txn.accountLast4}` : '-'],
    ['Other account', txn.counterpartyAccountLast4 ? `ending ${txn.counterpartyAccountLast4}` : '-'],
    ['Reference', txn.referenceNo ?? '-'],
    ['Balance after', txn.balanceAfter !== null ? inr(txn.balanceAfter) : '-'],
    ['Note', txn.note ?? '-'],
    ['Seen in', <SourceChips key="s" txn={txn} />],
  ];
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Transaction details">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span className="muted">{credit ? 'Money in' : 'Money out'}</span>
          <button className="btn icon-btn" onClick={onClose} aria-label="Close"><X size={16} /></button>
        </div>
        <div className={`amount num ${credit ? 'income' : ''}`}>{credit ? '+' : '-'}{inr(txn.amount)}</div>
        <div style={{ fontSize: 17, fontWeight: 600 }}>{cleanName(txn.counterparty)}</div>
        <dl className="kv">
          {rows.map(([k, v]) => (
            <div key={k} style={{ display: 'contents' }}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      </aside>
    </>
  );
}

export function ChartTip({ active, payload, label, format = inr }: {
  active?: boolean; payload?: { name?: string; value?: number; color?: string }[]; label?: string | number; format?: (n: number) => string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="card" style={{ padding: '10px 12px', boxShadow: 'var(--shadow)' }}>
      {label !== undefined && <div className="muted" style={{ marginBottom: 4 }}>{label}</div>}
      {payload.map((p) => (
        <div key={p.name} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="dot" style={{ background: p.color }} />
          <span className="muted">{p.name}</span>
          <span className="num" style={{ marginLeft: 'auto', fontWeight: 600 }}>{format(p.value ?? 0)}</span>
        </div>
      ))}
    </div>
  );
}

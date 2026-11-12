import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Download, Search } from 'lucide-react';
import { api, type Direction, type Txn, type TxnQuery } from '../api';
import { cleanName, dateTimeIst, inr, longDate } from '../format';
import { RangePicker, useRange } from '../range';
import { Card, CategoryAvatar, ErrorBox, SourceChips, TxnDrawer, useAsync } from '../ui';

const PAGE = 25;

function csvCell(value: string | number | null): string {
  let text = value === null ? '' : String(value);
  // Stops spreadsheets from running a payee name or note that starts like a formula.
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

function toCsv(items: Txn[]): string {
  const header = ['Date', 'Received (IST)', 'Direction', 'Amount', 'Payee', 'Category', 'Mode', 'Account', 'Reference', 'Balance after', 'Note', 'Seen in'];
  const lines = items.map((t) => [
    t.transactionDate, dateTimeIst(t.eventTimestamp), t.direction, t.amount, t.counterparty, t.category, t.paymentMode,
    t.accountLast4, t.referenceNo, t.balanceAfter, t.note, Array.from(new Set(t.sources.map((s) => s.type))).join('+'),
  ].map(csvCell).join(','));
  return [header.map(csvCell).join(','), ...lines].join('\r\n');
}

export default function Transactions() {
  const { range } = useRange();
  const [params] = useSearchParams();
  const [text, setText] = useState(params.get('q') ?? '');
  const [q, setQ] = useState(params.get('q') ?? '');
  const [direction, setDirection] = useState<Direction | ''>((params.get('direction') as Direction | null) ?? '');
  const [category, setCategory] = useState(params.get('category') ?? '');
  const [source, setSource] = useState('');
  const [min, setMin] = useState('');
  const [max, setMax] = useState('');
  const [sort, setSort] = useState<'date' | 'amount'>('date');
  const [order, setOrder] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<Txn | null>(null);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(id);
  }, [text]);

  // Links from the overview (a category or payee) land here with filters already set.
  const linked = params.toString();
  useEffect(() => {
    const next = new URLSearchParams(linked);
    setText(next.get('q') ?? '');
    setQ(next.get('q') ?? '');
    setDirection((next.get('direction') as Direction | null) ?? '');
    setCategory(next.get('category') ?? '');
  }, [linked]);

  const filters: TxnQuery = { from: range.from, to: range.to, direction, category, source, q, minAmount: min, maxAmount: max, sort, order };
  const key = JSON.stringify(filters);
  useEffect(() => setPage(0), [key]);

  const [askText, setAskText] = useState('');
  const [ask, setAsk] = useState('');
  useEffect(() => {
    const id = setTimeout(() => setAsk(askText.trim()), 400);
    return () => clearTimeout(id);
  }, [askText]);
  const spend = useAsync(
    () => (ask.length >= 2 ? api.spend(ask, range.from, range.to) : Promise.resolve(null)),
    [ask, range.from, range.to],
  );

  const data = useAsync(() => api.transactions({ ...filters, page, size: PAGE }), [key, page]);
  const result = data.data;
  const pages = result ? Math.max(1, Math.ceil(result.total / PAGE)) : 1;

  function sortBy(column: 'date' | 'amount') {
    if (sort === column) setOrder(order === 'desc' ? 'asc' : 'desc');
    else { setSort(column); setOrder('desc'); }
  }

  async function exportCsv() {
    setExporting(true);
    try {
      const all: Txn[] = [];
      for (let p = 0; p < 100; p++) {
        const part = await api.transactions({ ...filters, page: p, size: 200 });
        all.push(...part.items);
        if ((p + 1) * 200 >= part.total) break;
      }
      const url = URL.createObjectURL(new Blob([toCsv(all)], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `transactions-${range.from}-to-${range.to}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <div className="topbar">
        <div className="page-title">
          <h1>Transactions</h1>
          <p>{result ? `${result.total} found` : 'Loading...'}</p>
        </div>
        <RangePicker />
      </div>

      <Card title="How much did I spend on..." hint="for the dates chosen above">
        <label className="field search">
          <Search size={16} className="muted" />
          <input placeholder="dosa, tea, petrol, a payee or any category" value={askText} onChange={(e) => setAskText(e.target.value)} aria-label="Spend on" />
        </label>
        {spend.error && <ErrorBox message={spend.error} />}
        {spend.data && (
          <div style={{ marginTop: 12 }}>
            <div style={{ fontSize: 22, fontWeight: 700 }} className="num">{inr(spend.data.total)}</div>
            <div className="muted">
              {spend.data.count === 0
                ? `Nothing matched \u201c${spend.data.term}\u201d between ${longDate(spend.data.from)} and ${longDate(spend.data.to)}`
                : `${spend.data.count} payment${spend.data.count === 1 ? '' : 's'} between ${longDate(spend.data.from)} and ${longDate(spend.data.to)}`}
            </div>
            {spend.data.items.slice(0, 8).map((m) => (
              <div className="row" key={m.transaction.groupId} role="button" tabIndex={0} style={{ cursor: 'pointer' }}
                onClick={() => setOpen(m.transaction)} onKeyDown={(e) => e.key === 'Enter' && setOpen(m.transaction)}>
                <div className="grow">
                  <div className="title">{cleanName(m.transaction.counterparty)}</div>
                  <div className="sub">
                    {m.transaction.splits.length > 0 ? `Split of ${inr(m.transaction.amount)}` : (m.transaction.label ?? m.transaction.category)} · {m.transaction.transactionDate ? longDate(m.transaction.transactionDate) : dateTimeIst(m.transaction.eventTimestamp)}
                  </div>
                </div>
                <div className="num" style={{ fontWeight: 600 }}>{inr(m.matched)}</div>
              </div>
            ))}
            {spend.data.items.length > 8 && <div className="muted" style={{ marginTop: 6 }}>and {spend.data.items.length - 8} more</div>}
          </div>
        )}
        <p className="muted" style={{ marginTop: 10, fontSize: 12.5 }}>
          Matches the payee, your note or the category. For a payment you split on the phone, only the part you named counts.
        </p>
      </Card>
      <div style={{ height: 12 }} />

      <Card>
        <div className="filters">
          <label className="field search">
            <Search size={16} className="muted" />
            <input placeholder="Search payee, note, reference, amount" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search" />
          </label>
          <div className="seg" role="group" aria-label="Direction">
            {([['', 'All'], ['DEBIT', 'Out'], ['CREDIT', 'In']] as const).map(([v, label]) => (
              <button key={v} className={direction === v ? 'on' : ''} onClick={() => setDirection(v)}>{label}</button>
            ))}
          </div>
          <select className="field" value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category">
            <option value="">All categories</option>
            {result?.categories.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <select className="field" value={source} onChange={(e) => setSource(e.target.value)} aria-label="Source">
            <option value="">All sources</option>
            <option value="SMS">SMS</option>
            <option value="GMAIL">Email</option>
            <option value="NOTIFICATION">Notification</option>
          </select>
          <input className="field" style={{ width: 100 }} inputMode="decimal" placeholder="Min ₹" value={min} onChange={(e) => setMin(e.target.value.replace(/[^\d.]/g, ''))} aria-label="Minimum amount" />
          <input className="field" style={{ width: 100 }} inputMode="decimal" placeholder="Max ₹" value={max} onChange={(e) => setMax(e.target.value.replace(/[^\d.]/g, ''))} aria-label="Maximum amount" />
          {(q || direction || category || source || min || max) && (
            <button className="btn" onClick={() => { setText(''); setQ(''); setDirection(''); setCategory(''); setSource(''); setMin(''); setMax(''); }}>Clear filters</button>
          )}
          <button className="btn" onClick={exportCsv} disabled={exporting || !result?.total}><Download size={16} /> {exporting ? 'Preparing...' : 'CSV'}</button>
        </div>

        {data.error && <ErrorBox message={data.error} />}
        {result && (
          <div className="totals" style={{ marginBottom: 10 }}>
            <span>Out <strong className="num spend">{inr(result.totalDebit, true)}</strong></span>
            <span>In <strong className="num income">{inr(result.totalCredit, true)}</strong></span>
          </div>
        )}

        <div className="table-wrap" style={{ opacity: data.loading ? 0.6 : 1 }}>
          <table>
            <thead>
              <tr>
                <th>Payee</th>
                <th>Category</th>
                <th><button className="nav-button" style={{ color: 'inherit', background: 'none', border: 0, padding: 0 }} onClick={() => sortBy('date')}>Date {sort === 'date' ? (order === 'desc' ? '↓' : '↑') : ''}</button></th>
                <th>Mode</th>
                <th>Seen in</th>
                <th className="right"><button style={{ color: 'inherit', background: 'none', border: 0, padding: 0 }} onClick={() => sortBy('amount')}>Amount {sort === 'amount' ? (order === 'desc' ? '↓' : '↑') : ''}</button></th>
              </tr>
            </thead>
            <tbody>
              {result?.items.map((t) => (
                <tr key={t.groupId} className="click" onClick={() => setOpen(t)}>
                  <td>
                    <div className="cell-main">
                      <CategoryAvatar category={t.category} size={34} />
                      <div style={{ minWidth: 0 }}>
                        <div className="title">{cleanName(t.counterparty)}</div>
                        {(t.label || t.note) && <div className="sub">{t.label ? `\u201c${t.label}\u201d` : t.note}</div>}
                      </div>
                    </div>
                  </td>
                  <td className="muted">{t.splits.length > 0 ? t.splits.map((p) => `${p.name ?? p.category} ${inr(p.amount)}`).join(' · ') : t.category}</td>
                  <td className="muted" style={{ whiteSpace: 'nowrap' }}>{t.transactionDate ? longDate(t.transactionDate) : dateTimeIst(t.eventTimestamp)}</td>
                  <td className="muted">{t.paymentMode ?? '-'}</td>
                  <td><SourceChips txn={t} /></td>
                  <td className={`right num ${t.direction === 'CREDIT' ? 'income' : ''}`} style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
                    {t.direction === 'CREDIT' ? '+' : '-'}{inr(t.amount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {result && result.items.length === 0 && <div className="empty">Nothing matches these filters</div>}
        </div>

        <div className="pager">
          <span className="muted">Page {page + 1} of {pages}</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn icon-btn" disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="Previous page"><ChevronLeft size={16} /></button>
            <button className="btn icon-btn" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} aria-label="Next page"><ChevronRight size={16} /></button>
          </div>
        </div>
      </Card>
      <TxnDrawer txn={open} onClose={() => setOpen(null)} />
    </>
  );
}

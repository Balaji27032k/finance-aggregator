import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Area, AreaChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ArrowDownLeft, ArrowUpRight, Gauge, Landmark, PiggyBank, Wallet } from 'lucide-react';
import { api, type DayPoint, type Txn } from '../api';
import { categoryStyle } from '../categories';
import { ago, cleanName, inr, inrShort, longDate, parseIso, pctChange, shortDate, toIso } from '../format';
import { PaceCard } from '../PaceCard';
import { AiAnalyser } from '../AiAnalyser';
import { RangePicker, previousFor, useRange } from '../range';
import { Card, ChartTip, Delta, ErrorBox, Money, Skeleton, TxnDrawer, TxnRow, useAsync } from '../ui';

function greeting(): string {
  const hour = new Date().getHours();
  return hour < 5 ? 'Late night' : hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
}

function weekly(daily: DayPoint[]): DayPoint[] {
  const out = new Map<string, DayPoint>();
  for (const d of daily) {
    const date = parseIso(d.date);
    const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate() - ((date.getDay() + 6) % 7));
    const key = toIso(monday);
    const cur = out.get(key) ?? { date: key, income: 0, spend: 0 };
    cur.income += d.income;
    cur.spend += d.spend;
    out.set(key, cur);
  }
  return Array.from(out.values());
}

export default function Overview() {
  const { range } = useRange();
  const [open, setOpen] = useState<Txn | null>(null);
  const prev = previousFor(range);
  const summary = useAsync(() => api.summary(range.from, range.to, prev), [range.from, range.to, range.preset]);
  const recent = useAsync(() => api.transactions({ from: range.from, to: range.to, size: 7 }), [range.from, range.to]);

  const s = summary.data;
  const series = useMemo(() => (s ? (s.range.days > 92 ? weekly(s.daily) : s.daily) : []), [s]);
  const categoryTotal = s ? s.byCategory.reduce((sum, b) => sum + b.total, 0) : 0;

  return (
    <>
      <div className="topbar">
        <div className="page-title">
          <h1>{greeting()}</h1>
          <p>{s ? `Your money from ${longDate(s.range.from)} to ${longDate(s.range.to)}` : 'Loading...'}</p>
        </div>
        <RangePicker />
      </div>

      {summary.error && <ErrorBox message={summary.error} />}
      <AiAnalyser question={`Summarise my spending from ${range.from} to ${range.to}`} label={`${longDate(range.from)} to ${longDate(range.to)}`} />
      <div style={{ height: 16 }} />
      {!s && !summary.error && <div className="grid cols-4"><Skeleton /><Skeleton /><Skeleton /><Skeleton /></div>}

      {s && s.totals.count === 0 && s.balances.length === 0 && (
        <Card>
          <div className="empty">
            <strong>No transactions in this period yet.</strong>
            <p style={{ marginTop: 6 }}>Try a longer range above. If nothing ever shows up, open the phone app, check the Health tab and tap Sync.</p>
          </div>
        </Card>
      )}

      {s && (
        <div className="grid" style={{ opacity: summary.loading ? 0.6 : 1, transition: 'opacity 0.15s' }}>
          <div className="grid cols-4">
            <Card className="stat hero">
              <div className="label" title="Everything that left your accounts in this period"><ArrowUpRight size={16} className="spend" /> Spent</div>
              <div className="value num"><Money value={s.totals.spend} /></div>
              <div className="delta"><Delta value={pctChange(s.totals.spend, s.previous.spend)} goodWhenUp={false} /> vs {prev?.label ?? `the previous ${s.range.days} days`}</div>
            </Card>
            <Card className="stat">
              <div className="label" title="Everything that came into your accounts in this period"><ArrowDownLeft size={16} className="income" /> Received</div>
              <div className="value num"><Money value={s.totals.income} /></div>
              <div className="delta"><Delta value={pctChange(s.totals.income, s.previous.income)} goodWhenUp /> vs {prev?.label ?? `the previous ${s.range.days} days`}</div>
            </Card>
            <Card className="stat">
              <div className="label" title="Received minus spent"><PiggyBank size={16} /> Kept</div>
              <div className={`value num ${s.totals.net >= 0 ? 'income' : 'spend'}`}>{s.totals.net >= 0 ? '' : '-'}<Money value={Math.abs(s.totals.net)} /></div>
              <div className="delta">{s.savingsRatePercent !== null ? `${s.savingsRatePercent}% of what came in` : 'No income in this period'}</div>
            </Card>
            <Card className="stat">
              <div className="label" title="Spent divided by the number of days"><Gauge size={16} /> Average per day</div>
              <div className="value num"><Money value={s.totals.avgDailySpend} /></div>
              <div className="delta">{s.totals.count} transactions · biggest {inr(s.totals.largestExpense, true)}</div>
            </Card>
          </div>

          <div className="grid cols-main">
            <Card title="Money in and out" hint={s.range.days > 92 ? 'weekly' : 'daily'}>
              <div className="chart">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={series} margin={{ top: 6, right: 6, left: -10, bottom: 0 }}>
                    <defs>
                      <linearGradient id="gs" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#f43f5e" stopOpacity={0.45} />
                        <stop offset="100%" stopColor="#f43f5e" stopOpacity={0} />
                      </linearGradient>
                      <linearGradient id="gi" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#22c55e" stopOpacity={0.4} />
                        <stop offset="100%" stopColor="#22c55e" stopOpacity={0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid stroke="var(--grid)" vertical={false} />
                    <XAxis dataKey="date" tickFormatter={shortDate} tickLine={false} axisLine={false} minTickGap={36} stroke="var(--faint)" />
                    <YAxis tickFormatter={inrShort} tickLine={false} axisLine={false} stroke="var(--faint)" width={52} />
                    <Tooltip content={<ChartTip />} labelFormatter={(l) => shortDate(String(l))} />
                    <Area type="monotone" dataKey="income" name="In" stroke="#22c55e" strokeWidth={2} fill="url(#gi)" />
                    <Area type="monotone" dataKey="spend" name="Out" stroke="#f43f5e" strokeWidth={2} fill="url(#gs)" />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </Card>

            <Card title="Where it went" hint={inr(categoryTotal, true)}>
              {s.byCategory.length === 0 ? <div className="empty">No spending in this period</div> : (
                <div className="donut-wrap">
                  <div className="chart">
                    <ResponsiveContainer width="100%" height="100%">
                      <PieChart>
                        <Pie data={s.byCategory} dataKey="total" nameKey="key" innerRadius="62%" outerRadius="92%" paddingAngle={2} stroke="none">
                          {s.byCategory.map((b) => <Cell key={b.key} fill={categoryStyle(b.key).color} />)}
                        </Pie>
                        <Tooltip content={<ChartTip />} />
                      </PieChart>
                    </ResponsiveContainer>
                  </div>
                  <div className="legend">
                    {s.byCategory.slice(0, 6).map((b) => (
                      <Link className="legend-item link" key={b.key} to={`/transactions?direction=DEBIT&category=${encodeURIComponent(b.key)}`}
                        title={`See ${b.key} transactions`}>
                        <span className="dot" style={{ background: categoryStyle(b.key).color }} />
                        <span className="grow">{b.key}</span>
                        <span className="num muted">{Math.round((b.total / categoryTotal) * 100)}%</span>
                      </Link>
                    ))}
                  </div>
                </div>
              )}
            </Card>
          </div>

          <div className="grid cols-3">
            <PaceCard pace={s.projection} />

            <Card title="Latest balances" hint={s.balances.length > 0 ? `together ${inr(s.balances.reduce((sum, b) => sum + b.balance, 0), true)}` : 'from the newest bank message'}>
              {s.balances.length === 0 ? <div className="empty">No balances in your messages yet</div> : (
                <div className="rows">
                  {s.balances.slice(0, 4).map((b) => (
                    <div className="row" key={`${b.bankCode}${b.accountLast4}`}>
                      <span className="avatar" style={{ background: 'var(--surface-2)' }}><Landmark size={18} /></span>
                      <div className="grow">
                        <div className="title">{b.bankCode} ••{b.accountLast4}</div>
                        <div className="sub">{ago(b.asOf)}</div>
                      </div>
                      <div className="num" style={{ fontWeight: 600 }}>{inr(b.balance, true)}</div>
                    </div>
                  ))}
                </div>
              )}
            </Card>

            <Card title="Top payees">
              {s.topPayees.length === 0 ? <div className="empty">Nothing yet</div> : (
                <div className="rows">
                  {s.topPayees.slice(0, 5).map((p) => (
                    <Link className="row link" key={p.key} to={p.key === 'Unknown' ? '/transactions?direction=DEBIT' : `/transactions?direction=DEBIT&q=${encodeURIComponent(p.key)}`}
                      title="See these transactions">
                      <span className="avatar" style={{ background: 'var(--surface-2)' }}><Wallet size={18} /></span>
                      <div className="grow">
                        <div className="title">{cleanName(p.key)}</div>
                        <div className="bar"><span style={{ width: `${(p.total / s.topPayees[0].total) * 100}%`, background: 'var(--accent)' }} /></div>
                      </div>
                      <div className="num" style={{ fontWeight: 600 }}>{inr(p.total, true)}</div>
                    </Link>
                  ))}
                </div>
              )}
            </Card>
          </div>

          <Card title="Recent transactions" hint={<Link to="/transactions" className="muted">See all</Link>}>
            {recent.data && recent.data.items.length > 0 ? (
              <div className="rows">{recent.data.items.map((t) => <TxnRow key={t.groupId} txn={t} onOpen={setOpen} />)}</div>
            ) : (
              <div className="empty">{recent.loading ? 'Loading...' : 'No transactions in this period'}</div>
            )}
          </Card>
          <p className="muted" style={{ fontSize: 12.5 }}>
            Amounts come straight from your bank messages. A transfer between your own accounts shows as money out of one and money in to the other.
          </p>
        </div>
      )}
      <TxnDrawer txn={open} onClose={() => setOpen(null)} />
    </>
  );
}

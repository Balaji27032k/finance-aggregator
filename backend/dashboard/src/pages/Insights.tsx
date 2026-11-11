import { useState } from 'react';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { CalendarClock, Lightbulb, Repeat, Zap } from 'lucide-react';
import { api, type Bucket, type Summary, type Txn } from '../api';
import { categoryStyle } from '../categories';
import { cleanName, inr, inrShort, longDate, monthLabel, pctChange, shortDate } from '../format';
import { RangePicker, useRange } from '../range';
import { Heatmap } from '../Heatmap';
import { Card, ChartTip, ErrorBox, Skeleton, TxnDrawer, TxnRow, useAsync } from '../ui';

const FULL_DAY: Record<string, string> = {
  Mon: 'Monday', Tue: 'Tuesday', Wed: 'Wednesday', Thu: 'Thursday', Fri: 'Friday', Sat: 'Saturday', Sun: 'Sunday',
};

function highlights(s: Summary): string[] {
  const out: string[] = [];
  const change = pctChange(s.totals.spend, s.previous.spend);
  if (change !== null && s.previous.spend > 0) {
    const pct = Math.abs(Math.round(change));
    out.push(pct === 0
      ? `Spending is about the same as the previous ${s.range.days} days.`
      : `You spent ${pct}% ${change > 0 ? 'more' : 'less'} than in the previous ${s.range.days} days (${inr(s.previous.spend, true)}).`);
  }
  const total = s.byCategory.reduce((sum, b) => sum + b.total, 0);
  if (s.byCategory.length > 0 && total > 0) {
    const top = s.byCategory[0];
    out.push(`${top.key} is your biggest category: ${inr(top.total, true)}, ${Math.round((top.total / total) * 100)}% of spending.`);
  }
  const day = [...s.byWeekday].sort((a, b) => b.total - a.total)[0];
  if (day && day.total > 0) out.push(`You spend the most on ${FULL_DAY[day.key] ?? day.key}s (${inr(day.total, true)} in total).`);
  const hour = [...s.byHour].sort((a, b) => b.total - a.total)[0];
  if (hour && hour.total > 0) out.push(`Spending peaks around ${hour.key}:00 (India time).`);
  if (s.savingsRatePercent !== null) {
    out.push(s.savingsRatePercent >= 0
      ? `You kept ${s.savingsRatePercent}% of what came in.`
      : `You spent ${Math.abs(s.savingsRatePercent)}% more than what came in.`);
  }
  if (s.recurring.length > 0) {
    const monthly = s.recurring.reduce((sum, r) => sum + r.typicalAmount, 0);
    out.push(`${s.recurring.length} payment${s.recurring.length === 1 ? '' : 's'} repeat about monthly, around ${inr(monthly, true)} a month.`);
  }
  if (s.spikes.length > 0) {
    out.push(`Spending spiked on ${shortDate(s.spikes[0].date)}: ${inr(s.spikes[0].spend, true)}.`);
  }
  return out;
}

function BucketBars({ items, color, empty = 'Nothing yet' }: { items: Bucket[]; color?: (k: string) => string; empty?: string }) {
  if (items.length === 0) return <div className="empty">{empty}</div>;
  const max = items[0].total || 1;
  return (
    <div className="rows">
      {items.map((b) => (
        <div className="row" key={b.key}>
          <div className="grow">
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10 }}>
              <span className="title">{cleanName(b.key)}</span>
              <span className="num" style={{ fontWeight: 600 }}>{inr(b.total, true)}</span>
            </div>
            <div className="bar"><span style={{ width: `${(b.total / max) * 100}%`, background: color ? color(b.key) : 'var(--accent)' }} /></div>
            <div className="sub" style={{ marginTop: 3 }}>{b.count} transaction{b.count === 1 ? '' : 's'}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

export default function Insights() {
  const { range } = useRange();
  const [open, setOpen] = useState<Txn | null>(null);
  const summary = useAsync(() => api.summary(range.from, range.to), [range.from, range.to]);
  const s = summary.data;

  return (
    <>
      <div className="topbar">
        <div className="page-title">
          <h1>Insights</h1>
          <p>Patterns in how you spend</p>
        </div>
        <RangePicker />
      </div>
      {summary.error && <ErrorBox message={summary.error} />}
      {!s && !summary.error && <div className="grid cols-2"><Skeleton height={240} /><Skeleton height={240} /></div>}
      {s && (
        <div className="grid" style={{ opacity: summary.loading ? 0.6 : 1 }}>
          <Card title="Highlights">
            <div className="callouts">
              {highlights(s).map((text) => (
                <div className="callout" key={text}><Lightbulb size={18} /><span>{text}</span></div>
              ))}
              {highlights(s).length === 0 && <div className="empty">Not enough data in this period</div>}
            </div>
          </Card>

          <Card title="Spending calendar" hint="each square is a day; hover for the amount">
            <Heatmap daily={s.daily} />
          </Card>

          <Card title="Last 12 months" hint="money in and out">
            <div className="chart">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={s.monthly} margin={{ top: 6, right: 6, left: -10, bottom: 0 }}>
                  <CartesianGrid stroke="var(--grid)" vertical={false} />
                  <XAxis dataKey="month" tickFormatter={monthLabel} tickLine={false} axisLine={false} stroke="var(--faint)" />
                  <YAxis tickFormatter={inrShort} tickLine={false} axisLine={false} stroke="var(--faint)" width={52} />
                  <Tooltip content={<ChartTip />} cursor={{ fill: 'var(--grid)' }} labelFormatter={(l) => monthLabel(String(l))} />
                  <Bar dataKey="income" name="In" fill="#22c55e" radius={[6, 6, 0, 0]} />
                  <Bar dataKey="spend" name="Out" fill="#f43f5e" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>

          <div className="grid cols-2">
            <Card title="By day of week" hint="spending">
              <div className="chart small">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={s.byWeekday} margin={{ top: 6, right: 6, left: -10, bottom: 0 }}>
                    <CartesianGrid stroke="var(--grid)" vertical={false} />
                    <XAxis dataKey="key" tickLine={false} axisLine={false} stroke="var(--faint)" interval={0} />
                    <YAxis tickFormatter={inrShort} tickLine={false} axisLine={false} stroke="var(--faint)" width={52} />
                    <Tooltip content={<ChartTip />} cursor={{ fill: 'var(--grid)' }} />
                    <Bar dataKey="total" name="Spent" radius={[6, 6, 0, 0]}>
                      {s.byWeekday.map((d) => <Cell key={d.key} fill={d.key === 'Sat' || d.key === 'Sun' ? '#a78bfa' : '#6366f1'} />)}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>
            <Card title="By time of day" hint="India time">
              <div className="chart small">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={s.byHour} margin={{ top: 6, right: 6, left: -10, bottom: 0 }}>
                    <CartesianGrid stroke="var(--grid)" vertical={false} />
                    <XAxis dataKey="key" tickLine={false} axisLine={false} stroke="var(--faint)" interval={2} />
                    <YAxis tickFormatter={inrShort} tickLine={false} axisLine={false} stroke="var(--faint)" width={52} />
                    <Tooltip content={<ChartTip />} cursor={{ fill: 'var(--grid)' }} labelFormatter={(l) => `${l}:00`} />
                    <Bar dataKey="total" name="Spent" fill="#22d3ee" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>
          </div>

          <div className="grid cols-2">
            <Card title={<><Repeat size={14} style={{ verticalAlign: -2 }} /> Repeating payments</>} hint="spotted automatically">
              {s.recurring.length === 0 ? <div className="empty">No monthly pattern found yet</div> : (
                <div className="rows">
                  {s.recurring.slice(0, 8).map((r) => (
                    <div className="row" key={r.name}>
                      <span className="avatar" style={{ background: 'var(--surface-2)' }}><CalendarClock size={18} /></span>
                      <div className="grow">
                        <div className="title">{cleanName(r.name)}</div>
                        <div className="sub">{r.occurrences} times · next around {longDate(r.nextExpected)}</div>
                      </div>
                      <div className="num" style={{ fontWeight: 600 }}>{inr(r.typicalAmount, true)}</div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
            <Card title={<><Zap size={14} style={{ verticalAlign: -2 }} /> Unusually high days</>}>
              {s.spikes.length === 0 ? <div className="empty">No unusual days in this period</div> : (
                <div className="rows">
                  {s.spikes.map((d) => (
                    <div className="row" key={d.date}>
                      <div className="grow"><div className="title">{longDate(d.date)}</div></div>
                      <div className="num spend" style={{ fontWeight: 600 }}>{inr(d.spend, true)}</div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>

          <div className="grid cols-2">
            <Card title="Biggest expenses">
              {s.largestExpenses.length === 0 ? <div className="empty">Nothing yet</div> : (
                <div className="rows">{s.largestExpenses.map((t) => <TxnRow key={t.groupId} txn={t} onOpen={setOpen} />)}</div>
              )}
            </Card>
            <Card title="Where money came from">
              <BucketBars items={s.topIncomeSources.slice(0, 6)} color={() => '#22c55e'} empty="No income in this period" />
            </Card>
          </div>

          <div className="grid cols-3">
            <Card title="By category"><BucketBars items={s.byCategory.slice(0, 8)} color={(k) => categoryStyle(k).color} /></Card>
            <Card title="By payment mode"><BucketBars items={s.byMode} /></Card>
            <Card title="By bank"><BucketBars items={s.byBank} /></Card>
          </div>
        </div>
      )}
      <TxnDrawer txn={open} onClose={() => setOpen(null)} />
    </>
  );
}

import { useState } from 'react';
import { Pencil } from 'lucide-react';
import type { Projection } from './api';
import { useBudget } from './budget';
import { inr, monthLong } from './format';
import { Card } from './ui';

/** Month-to-date spending against the days gone by, and against a budget if one is set. */
export function PaceCard({ pace }: { pace: Projection }) {
  const [budget, setBudget] = useBudget();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const dayShare = Math.min(100, (pace.daysElapsed / pace.daysInMonth) * 100);
  const daysLeft = pace.daysInMonth - pace.daysElapsed;

  function save() {
    const value = Number(draft.replace(/[^\d.]/g, ''));
    setBudget(value > 0 ? value : null);
    setEditing(false);
  }

  const edit = (
    <button className="btn icon-btn" aria-label={budget ? 'Change budget' : 'Set a budget'}
      onClick={() => { setDraft(budget ? String(budget) : ''); setEditing(true); }}>
      <Pencil size={14} />
    </button>
  );

  return (
    <Card title="This month's pace" hint={monthLong(pace.month)}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
        <div>
          <div className="num" style={{ fontSize: 26, fontWeight: 700 }}>{inr(pace.spentSoFar, true)}</div>
          <div className="muted">spent in {pace.daysElapsed} of {pace.daysInMonth} days</div>
        </div>
        {!editing && budget !== null && edit}
      </div>

      {editing && (
        <form style={{ display: 'flex', gap: 8, marginTop: 12 }} onSubmit={(e) => { e.preventDefault(); save(); }}>
          <input className="field" style={{ flex: 1 }} inputMode="numeric" autoFocus placeholder="Monthly budget in rupees"
            value={draft} onChange={(e) => setDraft(e.target.value.replace(/[^\d]/g, ''))} aria-label="Monthly budget" />
          <button className="btn primary" type="submit">Save</button>
          <button className="btn" type="button" onClick={() => setEditing(false)}>Cancel</button>
        </form>
      )}

      {budget ? (
        <>
          <div className="bar" style={{ height: 12, marginTop: 16, position: 'relative', overflow: 'visible' }}>
            <span style={{
              width: `${Math.min(100, (pace.spentSoFar / budget) * 100)}%`,
              background: pace.spentSoFar > budget ? 'var(--spend)' : pace.projected > budget ? 'var(--warn)' : 'var(--income)',
            }} />
            <i title="Where you would be if spending were even through the month"
              style={{ position: 'absolute', left: `${dayShare}%`, top: -4, bottom: -4, width: 2, background: 'var(--text)', opacity: 0.6 }} />
          </div>
          <div className="muted" style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6, fontSize: 12 }}>
            <span>{Math.round((pace.spentSoFar / budget) * 100)}% of {inr(budget, true)}</span>
            <span>line = even pace</span>
          </div>
          <p style={{ marginTop: 12 }}>
            {pace.spentSoFar > budget
              ? <>You are <strong className="spend num">{inr(pace.spentSoFar - budget, true)}</strong> over budget.</>
              : pace.projected > budget
                ? <>At this pace you will pass the budget by about <strong className="num">{inr(pace.projected - budget, true)}</strong>.
                  To stay within it, keep to about <strong className="num">{inr(Math.max(0, (budget - pace.spentSoFar) / Math.max(daysLeft, 1)), true)}</strong> a day.</>
                : <>On track. You can spend about <strong className="num">{inr(Math.max(0, (budget - pace.spentSoFar) / Math.max(daysLeft, 1)), true)}</strong> a day for the rest of the month.</>}
          </p>
        </>
      ) : (
        <>
          <div className="bar" style={{ height: 10, marginTop: 14 }}>
            <span style={{ width: `${dayShare}%`, background: 'var(--accent)' }} />
          </div>
          <p style={{ marginTop: 14 }}>
            At this pace the month ends near <strong className="num">{inr(pace.projected, true)}</strong>.
            {!editing && <> <button className="btn" style={{ padding: '4px 10px', marginLeft: 6 }} onClick={() => { setDraft(''); setEditing(true); }}>Set a budget</button></>}
          </p>
          <p className="muted" style={{ fontSize: 12.5 }}>
            Spent so far plus your usual {inr(pace.dailyRate, true)} a day for the days left. One-off big payments are not repeated.
          </p>
        </>
      )}
    </Card>
  );
}

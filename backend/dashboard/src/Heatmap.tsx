import { useMemo } from 'react';
import type { DayPoint } from './api';
import { inr, longDate, parseIso } from './format';

/** One square per day, in week columns (Monday on top); darker means more spent. */
export function Heatmap({ daily }: { daily: DayPoint[] }) {
  const { cells, weeks, max } = useMemo(() => {
    const max = Math.max(0, ...daily.map((d) => d.spend));
    const lead = daily.length ? (parseIso(daily[0].date).getDay() + 6) % 7 : 0;
    const cells: (DayPoint | null)[] = [...Array<null>(lead).fill(null), ...daily];
    return { cells, weeks: Math.ceil(cells.length / 7), max };
  }, [daily]);

  const level = (spend: number) => {
    if (spend <= 0 || max <= 0) return 0;
    return Math.min(4, 1 + Math.floor((spend / max) * 4 - 0.001));
  };
  const shade = [0, 22, 42, 66, 92];

  return (
    <div>
      <div className="heatmap" style={{ gridTemplateColumns: `repeat(${weeks}, minmax(10px, 1fr))` }} role="img"
        aria-label="Spending per day, darker squares are bigger days">
        {cells.map((d, i) => d === null
          ? <span key={`b${i}`} className="heat-cell blank" />
          : (
            <span key={d.date} className="heat-cell" title={`${longDate(d.date)}: ${d.spend > 0 ? inr(d.spend, true) : 'nothing spent'}`}
              style={{ background: level(d.spend) === 0 ? 'var(--surface-2)' : `color-mix(in srgb, var(--spend) ${shade[level(d.spend)]}%, var(--surface-2))` }} />
          ))}
      </div>
      <div className="heat-legend">
        <span>Less</span>
        {shade.map((p, i) => (
          <span key={p} className="heat-cell" style={{ width: 14, height: 14, background: i === 0 ? 'var(--surface-2)' : `color-mix(in srgb, var(--spend) ${p}%, var(--surface-2))` }} />
        ))}
        <span>More</span>
      </div>
    </div>
  );
}

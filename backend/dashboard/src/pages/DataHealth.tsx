import { CheckCircle2, Clock, GitMerge, Mail, MessageSquare, TriangleAlert, Bell } from 'lucide-react';
import { api } from '../api';
import { ago, dateTimeIst, sourceLabel } from '../format';
import { Card, ErrorBox, Skeleton, useAsync } from '../ui';

const ICONS: Record<string, typeof Mail> = { SMS: MessageSquare, GMAIL: Mail, NOTIFICATION: Bell };

export default function DataHealth() {
  const health = useAsync(() => api.health(), []);
  const h = health.data;
  return (
    <>
      <div className="topbar">
        <div className="page-title">
          <h1>Data health</h1>
          <p>What reached the server and when</p>
        </div>
      </div>
      {health.error && <ErrorBox message={health.error} />}
      {!h && !health.error && <div className="grid cols-3"><Skeleton /><Skeleton /><Skeleton /></div>}
      {h && (
        <div className="grid">
          <div className="grid cols-3">
            <Card className="stat">
              <div className="label"><CheckCircle2 size={16} className="income" /> Transactions</div>
              <div className="value num">{h.transactions}</div>
              <div className="delta">since {h.firstEvent ? dateTimeIst(h.firstEvent) : 'the start'}</div>
            </Card>
            <Card className="stat">
              <div className="label"><GitMerge size={16} /> Confirmed by more than one source</div>
              <div className="value num">{h.multiChannelTransactions}</div>
              <div className="delta">same transaction seen as SMS and e-mail, counted once</div>
            </Card>
            <Card className="stat">
              <div className="label"><Clock size={16} /> Latest message</div>
              <div className="value">{ago(h.bySource.map((s) => s.latest).filter((v): v is string => !!v).sort().pop() ?? null, new Date(h.serverTime).getTime())}</div>
              <div className="delta">if this is old, open the phone app and tap Sync</div>
            </Card>
          </div>
          <div className="grid cols-2">
            <Card title="By source">
              <div className="rows">
                {h.bySource.map((s) => {
                  const Icon = ICONS[s.key] ?? TriangleAlert;
                  return (
                    <div className="row" key={s.key}>
                      <span className="avatar" style={{ background: 'var(--surface-2)' }}><Icon size={18} /></span>
                      <div className="grow">
                        <div className="title">{sourceLabel(s.key)}</div>
                        <div className="sub">last {ago(s.latest, new Date(h.serverTime).getTime())}</div>
                      </div>
                      <div className="num" style={{ fontWeight: 600 }}>{s.count}</div>
                    </div>
                  );
                })}
                {h.bySource.length === 0 && <div className="empty">No data yet</div>}
              </div>
            </Card>
            <Card title="By bank or sender">
              <div className="rows">
                {h.byBank.map((b) => (
                  <div className="row" key={b.key}>
                    <div className="grow">
                      <div className="title">{b.key}</div>
                      <div className="sub">last {ago(b.latest, new Date(h.serverTime).getTime())}</div>
                    </div>
                    <div className="num" style={{ fontWeight: 600 }}>{b.count}</div>
                  </div>
                ))}
                {h.byBank.length === 0 && <div className="empty">No data yet</div>}
              </div>
            </Card>
          </div>
        </div>
      )}
    </>
  );
}

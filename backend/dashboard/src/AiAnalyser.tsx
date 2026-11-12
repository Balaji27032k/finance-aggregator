import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { api, type AskReply } from './api';
import { inr } from './format';
import { Card, ErrorBox } from './ui';

function cacheKey(question: string): string {
  return `finance.analysis.${new Date().toISOString().slice(0, 10)}.${question}`;
}

/**
 * An AI summary of what the page is showing. It runs only when asked, and the answer is kept for the rest of the
 * day so asking again about the same view costs nothing.
 */
export function AiAnalyser({ question, label }: { question: string; label: string }) {
  const [reply, setReply] = useState<AskReply | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    try {
      const saved = sessionStorage.getItem(cacheKey(question));
      setReply(saved ? (JSON.parse(saved) as AskReply) : null);
    } catch {
      setReply(null);
    }
  }, [question]);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const result = await api.ask(question, []);
      setReply(result);
      try {
        sessionStorage.setItem(cacheKey(question), JSON.stringify(result));
      } catch {
        /* storage full or blocked: the answer is still shown */
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={<><Sparkles size={15} style={{ verticalAlign: -2, marginRight: 6 }} />AI analysis</>} hint={label}>
      {!reply && !busy && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button className="btn primary" onClick={run}><Sparkles size={16} /> Analyse this view</button>
          <span className="muted">A short plain-words summary. Runs only when you tap.</span>
        </div>
      )}
      {busy && <div className="muted">Analysing... this can take a minute if the server was asleep.</div>}
      {error && <ErrorBox message={error} />}
      {reply && !busy && (
        <div className="ask-details" style={{ marginTop: 0 }}>
          <div style={{ fontSize: 15 }}>{reply.answer}</div>
          {reply.breakdown.length > 0 && (
            <div>
              <div className="muted" style={{ marginBottom: 4 }}>{reply.breakdownTitle}</div>
              {reply.breakdown.slice(0, 5).map((g) => (
                <div className="ask-line" key={g.name}><span>{g.name}</span><span className="num">{inr(g.total, true)}</span></div>
              ))}
            </div>
          )}
          <div className="ask-meta">
            <span className="chip">{reply.aiUsed ? 'AI' : 'Built-in search'}</span>
            {reply.note && <span className="muted">{reply.note}</span>}
            <button className="btn" onClick={run}>Refresh</button>
            <Link className="btn" to={`/ask?q=${encodeURIComponent(question)}`}>Continue in chat</Link>
          </div>
        </div>
      )}
    </Card>
  );
}

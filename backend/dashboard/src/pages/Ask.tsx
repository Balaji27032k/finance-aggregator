import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Send, Sparkles } from 'lucide-react';
import { api, type AskReply, type AskTurn } from '../api';
import { cleanName, inr, longDate } from '../format';

interface Message {
  role: 'user' | 'assistant';
  text: string;
  reply?: AskReply;
  failed?: boolean;
}

const STARTERS = [
  'How much did I spend on food this month?',
  'What were my biggest expenses last month?',
  'Give me a summary of this year',
  'How much on dosa in the last 12 months?',
];

function Details({ reply }: { reply: AskReply }) {
  // The headline is the exact total; related names can be added with the switch.
  const [withRelated, setWithRelated] = useState(false);
  const exact = reply.exact;
  const related = reply.related;
  const hasRelated = !!related && related.count > 0;
  return (
    <div className="ask-details">
      {exact && (
        <div className="ask-totals">
          <div>
            <div className="muted">{reply.terms.join(' / ')} · {reply.periodLabel}</div>
            <div className="ask-total num">{inr((exact.total ?? 0) + (withRelated && related ? related.total : 0), true)}</div>
            <div className="muted">
              {exact.count + (withRelated && related ? related.count : 0)} payment{exact.count + (withRelated && related ? related.count : 0) === 1 ? '' : 's'}
              {withRelated ? ' including related' : ' exactly matching'}
            </div>
          </div>
          {hasRelated && (
            <label className="ask-switch">
              <input type="checkbox" checked={withRelated} onChange={(e) => setWithRelated(e.target.checked)} />
              <span>Include related ({inr(related!.total, true)})</span>
            </label>
          )}
        </div>
      )}
      {reply.breakdown.length > 0 && (
        <div>
          <div className="muted" style={{ marginBottom: 4 }}>{reply.breakdownTitle}</div>
          {reply.breakdown.map((g) => (
            <div className="ask-line" key={g.name}>
              <span>{g.name} <span className="muted">· {g.count}</span></span>
              <span className="num">{inr(g.total, true)}</span>
            </div>
          ))}
        </div>
      )}
      {reply.items.length > 0 && (
        <div>
          <div className="muted" style={{ marginBottom: 4 }}>{reply.items.length === 1 ? 'Payment' : 'Payments'}</div>
          {reply.items.slice(0, 8).map((i, n) => (
            <div className="ask-line" key={n}>
              <span>
                {cleanName(i.title)} <span className="muted">· {i.name}{i.related ? ' (related)' : ''} · {longDate(i.date)}</span>
              </span>
              <span className="num">{inr(i.amount)}</span>
            </div>
          ))}
          {reply.items.length > 8 && <div className="muted">and {reply.items.length - 8} more</div>}
        </div>
      )}
    </div>
  );
}

export default function Ask() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [params, setParams] = useSearchParams();
  const end = useRef<HTMLDivElement>(null);
  const started = useRef(false);

  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, busy]);

  async function send(question: string) {
    const q = question.trim();
    if (!q || busy) return;
    const history: AskTurn[] = messages.filter((m) => !m.failed).slice(-8).map((m) => ({ role: m.role, text: m.text.slice(0, 600) }));
    setMessages((m) => [...m, { role: 'user', text: q }]);
    setText('');
    setBusy(true);
    try {
      const reply = await api.ask(q, history);
      setMessages((m) => [...m, { role: 'assistant', text: reply.answer, reply }]);
    } catch (e) {
      setMessages((m) => [...m, { role: 'assistant', text: (e as Error).message, failed: true }]);
    } finally {
      setBusy(false);
    }
  }

  // A link from another page (for example "Ask about this view") opens the chat with a question already sent.
  const linked = params.get('q');
  useEffect(() => {
    if (linked && !started.current) {
      started.current = true;
      setParams({}, { replace: true });
      void send(linked);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linked]);

  const last = messages[messages.length - 1]?.reply;

  return (
    <>
      <div className="topbar">
        <div className="page-title">
          <h1>Ask AI about your spending</h1>
          <p>Ask in your own words. Totals are always added up from your own data.</p>
        </div>
      </div>

      <div className="chat">
        <div className="chat-scroll">
          {messages.length === 0 && (
            <div className="chat-empty">
              <Sparkles size={28} className="muted" />
              <p>Try one of these, or type your own question.</p>
              <div className="chip-row">
                {STARTERS.map((s) => <button key={s} className="btn" onClick={() => send(s)}>{s}</button>)}
              </div>
            </div>
          )}
          {messages.map((m, i) => (
            <div key={i} className={`bubble-row ${m.role}`}>
              <div className={`bubble ${m.role}${m.failed ? ' failed' : ''}`}>
                <div>{m.text}</div>
                {m.reply && <Details reply={m.reply} />}
                {m.reply && (
                  <div className="ask-meta">
                    <span className="chip">{m.reply.aiUsed ? 'AI' : 'Built-in search'}</span>
                    {m.reply.note && <span className="muted">{m.reply.note}</span>}
                    {m.reply.sentToAi.length > 0 && (
                      <details>
                        <summary>What was sent to the AI</summary>
                        <p className="muted" style={{ margin: '6px 0' }}>Account numbers, UPI ids, phone numbers, references and balances are removed before anything is sent.</p>
                        {m.reply.sentToAi.map((s, n) => <pre key={n}>{s}</pre>)}
                      </details>
                    )}
                  </div>
                )}
              </div>
            </div>
          ))}
          {busy && (
            <div className="bubble-row assistant">
              <div className="bubble assistant muted">Thinking... the first question can take a minute if the server was asleep.</div>
            </div>
          )}
          {!busy && last && last.suggestions.length > 0 && (
            <div className="chip-row" style={{ marginTop: 6 }}>
              {last.suggestions.map((s) => <button key={s} className="btn" onClick={() => send(s)}>{s}</button>)}
            </div>
          )}
          <div ref={end} />
        </div>

        <form className="chat-input" onSubmit={(e) => { e.preventDefault(); void send(text); }}>
          <input
            className="field"
            placeholder="For example: how much on masala dosa last year?"
            value={text}
            maxLength={300}
            onChange={(e) => setText(e.target.value)}
            aria-label="Your question"
          />
          <button className="btn primary" type="submit" disabled={busy || !text.trim()} aria-label="Send"><Send size={16} /> Ask</button>
        </form>
      </div>
    </>
  );
}

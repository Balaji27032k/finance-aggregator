import { useEffect, useState, type FormEvent } from 'react';
import { Eye, EyeOff, LockKeyhole, TrendingUp } from 'lucide-react';
import { api, ApiError } from '../api';

export default function Login({ onSignedIn }: { onSignedIn: (user: string) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    document.title = 'Sign in · Finance';
  }, []);

  // The free server sleeps when idle; say so instead of leaving the button spinning without explanation.
  useEffect(() => {
    if (!busy) {
      setSlow(false);
      return;
    }
    const id = setTimeout(() => setSlow(true), 4000);
    return () => clearTimeout(id);
  }, [busy]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await api.login(username.trim(), password);
      onSignedIn(me.username);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Sign in failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <div className="login-card">
        <div className="brand" style={{ padding: 0 }}>
          <span className="brand-mark"><TrendingUp size={18} /></span>
          Finance
        </div>
        <p className="muted" style={{ marginTop: 10 }}>Sign in to see your money at a glance.</p>
        <form onSubmit={submit}>
          <input className="field" placeholder="User name" autoComplete="username" value={username}
            onChange={(e) => setUsername(e.target.value)} required maxLength={100} autoFocus />
          <div style={{ position: 'relative' }}>
            <input className="field" style={{ width: '100%', paddingRight: 44 }} type={visible ? 'text' : 'password'} placeholder="Password"
              autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required maxLength={200} />
            <button type="button" className="btn icon-btn" aria-label={visible ? 'Hide password' : 'Show password'}
              style={{ position: 'absolute', right: 4, top: 3, border: 0, background: 'none' }} onClick={() => setVisible((v) => !v)}>
              {visible ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
          {error && <div className="error" role="alert">{error}</div>}
          {slow && <div className="notice">Waking the server up. This can take up to a minute the first time.</div>}
          <button className="btn primary" disabled={busy || !username || !password}>
            <LockKeyhole size={16} /> {busy ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}

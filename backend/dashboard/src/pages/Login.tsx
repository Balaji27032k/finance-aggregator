import { useState, type FormEvent } from 'react';
import { LockKeyhole, TrendingUp } from 'lucide-react';
import { api, ApiError } from '../api';

export default function Login({ onSignedIn }: { onSignedIn: (user: string) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
          <input className="field" type="password" placeholder="Password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required maxLength={200} />
          {error && <div className="error" role="alert">{error}</div>}
          <button className="btn primary" disabled={busy || !username || !password}>
            <LockKeyhole size={16} /> {busy ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}

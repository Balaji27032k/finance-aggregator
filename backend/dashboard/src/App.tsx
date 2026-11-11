import { useCallback, useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { Activity, LayoutDashboard, LineChart, ListOrdered, LogOut, Moon, Sun, TrendingUp } from 'lucide-react';
import { api, setUnauthorizedListener } from './api';
import { RangeProvider } from './range';
import Login from './pages/Login';
import Overview from './pages/Overview';
import Insights from './pages/Insights';
import Transactions from './pages/Transactions';
import DataHealth from './pages/DataHealth';

type Theme = 'dark' | 'light';

function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('finance.theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('finance.theme', theme);
  }, [theme]);
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))];
}

export default function App() {
  const [theme, toggleTheme] = useTheme();
  const [user, setUser] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    setUnauthorizedListener(() => setUser(null));
    api.me().then((me) => setUser(me.username)).catch(() => setUser(null));
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setUser(null);
    }
  }, []);

  if (user === undefined) return <div className="login"><div className="muted">Loading...</div></div>;
  if (user === null) return <Login onSignedIn={setUser} />;

  return (
    <BrowserRouter>
      <RangeProvider>
        <div className="app">
          <nav className="sidebar" aria-label="Main">
            <div className="brand">
              <span className="brand-mark"><TrendingUp size={18} /></span>
              Finance
            </div>
            <NavLink to="/" end className="nav-link"><LayoutDashboard size={18} /><span>Overview</span></NavLink>
            <NavLink to="/insights" className="nav-link"><LineChart size={18} /><span>Insights</span></NavLink>
            <NavLink to="/transactions" className="nav-link"><ListOrdered size={18} /><span>Transactions</span></NavLink>
            <NavLink to="/data" className="nav-link"><Activity size={18} /><span>Data health</span></NavLink>
            <div className="sidebar-foot">
              <button className="nav-link nav-button" onClick={toggleTheme} aria-label="Switch theme">
                {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
                <span>{theme === 'dark' ? 'Light mode' : 'Dark mode'}</span>
              </button>
              <button className="nav-link nav-button" onClick={signOut} aria-label="Sign out">
                <LogOut size={18} /><span>Sign out ({user})</span>
              </button>
            </div>
          </nav>
          <main className="main">
            <Routes>
              <Route path="/" element={<Overview />} />
              <Route path="/insights" element={<Insights />} />
              <Route path="/transactions" element={<Transactions />} />
              <Route path="/data" element={<DataHealth />} />
              <Route path="*" element={<Overview />} />
            </Routes>
          </main>
        </div>
      </RangeProvider>
    </BrowserRouter>
  );
}

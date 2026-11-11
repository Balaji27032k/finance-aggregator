import { useCallback, useState } from 'react';

const KEY = 'finance.budget';

/** Monthly spending budget, kept in this browser only. */
export function useBudget(): [number | null, (value: number | null) => void] {
  const [budget, setBudgetState] = useState<number | null>(() => {
    const saved = Number(localStorage.getItem(KEY));
    return Number.isFinite(saved) && saved > 0 ? saved : null;
  });
  const setBudget = useCallback((value: number | null) => {
    if (value === null || !(value > 0)) {
      localStorage.removeItem(KEY);
      setBudgetState(null);
    } else {
      localStorage.setItem(KEY, String(Math.round(value)));
      setBudgetState(Math.round(value));
    }
  }, []);
  return [budget, setBudget];
}

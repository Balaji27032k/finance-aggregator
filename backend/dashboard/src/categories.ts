import {
  ArrowDownLeft, Banknote, Briefcase, CircleDot, Car, Clapperboard, HeartPulse, House, Landmark, Percent, Receipt,
  ShoppingBag, TrendingUp, Utensils,
  type LucideIcon,
} from 'lucide-react';

interface Style { color: string; icon: LucideIcon }

const STYLES: Record<string, Style> = {
  'Food & drinks': { color: '#f59e0b', icon: Utensils },
  'Transport': { color: '#38bdf8', icon: Car },
  'Shopping': { color: '#ec4899', icon: ShoppingBag },
  'Bills & recharge': { color: '#a78bfa', icon: Receipt },
  'Rent & housing': { color: '#f472b6', icon: House },
  'Health': { color: '#34d399', icon: HeartPulse },
  'Investments': { color: '#22c55e', icon: TrendingUp },
  'Entertainment & apps': { color: '#fb7185', icon: Clapperboard },
  'Cash withdrawal': { color: '#94a3b8', icon: Banknote },
  'Cash deposit': { color: '#14b8a6', icon: Landmark },
  'Salary': { color: '#22c55e', icon: Briefcase },
  'Money received': { color: '#2dd4bf', icon: ArrowDownLeft },
  'Interest': { color: '#84cc16', icon: Percent },
  'Other': { color: '#64748b', icon: CircleDot },
};

export function categoryStyle(category: string): Style {
  return STYLES[category] ?? STYLES['Other'];
}

export const PALETTE = ['#6366f1', '#22d3ee', '#f59e0b', '#ec4899', '#34d399', '#a78bfa', '#fb7185', '#38bdf8', '#84cc16', '#f97316'];

const inrFull = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 });
const inrWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

/** Rupees with Indian digit grouping; paise only when they exist and the amount is small. */
export function inr(value: number, whole = false): string {
  return (whole || Math.abs(value) >= 100000 ? inrWhole : inrFull).format(value);
}

/** Short form for chart axes: 1.2L, 45k. */
export function inrShort(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 10000000) return `${trim(value / 10000000)}Cr`;
  if (abs >= 100000) return `${trim(value / 100000)}L`;
  if (abs >= 1000) return `${trim(value / 1000)}k`;
  return String(Math.round(value));
}

function trim(n: number): string {
  return n.toFixed(1).replace(/\.0$/, '');
}

export function toIso(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function parseIso(value: string): Date {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function shortDate(value: string): string {
  return parseIso(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

export function longDate(value: string): string {
  return parseIso(value).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function monthLabel(value: string): string {
  const [y, m] = value.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-IN', { month: 'short' }) + (m === 1 ? ` ${String(y).slice(2)}` : '');
}

export function monthLong(value: string): string {
  const [y, m] = value.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

export function dateTimeIst(value: string): string {
  return new Date(value).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

export function timeIst(value: string): string {
  return new Date(value).toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour: 'numeric', minute: '2-digit' });
}

export function ago(value: string | null, now = Date.now()): string {
  if (!value) return 'never';
  const minutes = Math.max(0, Math.round((now - new Date(value).getTime()) / 60000));
  if (minutes < 2) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

export function pctChange(current: number, previous: number): number | null {
  if (previous === 0) return null;
  return ((current - previous) / previous) * 100;
}

export function sourceLabel(type: string): string {
  switch (type) {
    case 'GMAIL': return 'Email';
    case 'SMS': return 'SMS';
    case 'NOTIFICATION': return 'Notification';
    default: return type;
  }
}

export function cleanName(name: string | null): string {
  if (!name) return 'Unknown';
  return name.length > 3 && name === name.toUpperCase()
    ? name.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase())
    : name;
}

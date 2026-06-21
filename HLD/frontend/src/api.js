// Thin wrapper around the backend API. All paths are same-origin thanks to the Vite proxy.

export async function fetchSuggestions(prefix, ranking, signal) {
  const params = new URLSearchParams({ q: prefix, ranking });
  const res = await fetch(`/suggest?${params}`, { signal });
  if (!res.ok) throw new Error(`suggest failed: ${res.status}`);
  return res.json();
}

export async function submitSearch(query) {
  const res = await fetch('/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  });
  if (!res.ok) throw new Error(`search failed: ${res.status}`);
  return res.json();
}

export async function fetchTrending(limit = 10) {
  const res = await fetch(`/trending?limit=${limit}`);
  if (!res.ok) throw new Error(`trending failed: ${res.status}`);
  return res.json();
}

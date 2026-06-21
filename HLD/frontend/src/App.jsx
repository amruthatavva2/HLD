import { useEffect, useState, useCallback } from 'react';
import SearchBox from './components/SearchBox.jsx';
import SearchResult from './components/SearchResult.jsx';
import TrendingPanel from './components/TrendingPanel.jsx';
import { fetchSuggestions, submitSearch, fetchTrending } from './api.js';

/**
 * Top-level app. Owns all state and wires together the search box, the dummy
 * search-response panel, and the trending panel.
 *
 * Key behaviours required by the assignment:
 *  - suggestions update as the user types, but requests are DEBOUNCED (200ms) and the
 *    in-flight request is aborted when the query changes -> fewer backend calls;
 *  - submit on Enter or the Search button -> shows the dummy {"message":"Searched"};
 *  - a ranking toggle (recency vs basic) makes the two ranking modes demonstrable;
 *  - trending refreshes on a timer and right after each submission.
 */
export default function App() {
  const [query, setQuery] = useState('');
  const [ranking, setRanking] = useState('recency');
  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [showDropdown, setShowDropdown] = useState(false);
  const [result, setResult] = useState(null);
  const [trending, setTrending] = useState([]);

  // Debounced + abortable suggestion fetch.
  useEffect(() => {
    const q = query.trim();
    if (!q) {
      setSuggestions([]);
      setError(null);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    const timer = setTimeout(async () => {
      try {
        const data = await fetchSuggestions(q, ranking, controller.signal);
        setSuggestions(data);
        setActiveIndex(-1);
        setError(null);
      } catch (e) {
        if (e.name !== 'AbortError') setError('Could not load suggestions');
      } finally {
        setLoading(false);
      }
    }, 200);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, ranking]);

  const loadTrending = useCallback(async () => {
    try {
      setTrending(await fetchTrending(10));
    } catch {
      /* trending is best-effort; ignore transient errors */
    }
  }, []);

  // Poll trending so the "live" recency window is visible in the UI.
  useEffect(() => {
    loadTrending();
    const id = setInterval(loadTrending, 5000);
    return () => clearInterval(id);
  }, [loadTrending]);

  async function doSearch(text) {
    const q = (text ?? query).trim();
    if (!q) return;
    setShowDropdown(false);
    try {
      const res = await submitSearch(q);
      setResult({ query: q, message: res.message });
      loadTrending(); // surfaced in trending almost immediately
    } catch {
      setError('Search submission failed');
    }
  }

  function onChange(value) {
    setQuery(value);
    setShowDropdown(true);
  }

  function onSelect(s) {
    setQuery(s.query);
    doSearch(s.query);
  }

  function onKeyDown(e) {
    const open = showDropdown && suggestions.length > 0;
    if (e.key === 'Enter') {
      e.preventDefault();
      if (open && activeIndex >= 0) onSelect(suggestions[activeIndex]);
      else doSearch();
    } else if (e.key === 'ArrowDown' && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp' && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, -1));
    } else if (e.key === 'Escape') {
      setShowDropdown(false);
    }
  }

  return (
    <div className="page">
      <header className="header">
        <h1>Search Typeahead</h1>
      </header>

      <div className="ranking-toggle" role="group" aria-label="Ranking mode">
        <span>Ranking:</span>
        <button
          className={ranking === 'recency' ? 'active' : ''}
          onClick={() => setRanking('recency')}
        >
          Recency
        </button>
        <button
          className={ranking === 'basic' ? 'active' : ''}
          onClick={() => setRanking('basic')}
        >
          Popularity
        </button>
      </div>

      <SearchBox
        query={query}
        onChange={onChange}
        onKeyDown={onKeyDown}
        onFocus={() => setShowDropdown(true)}
        onSubmitClick={() => doSearch()}
        suggestions={suggestions}
        activeIndex={activeIndex}
        showDropdown={showDropdown}
        loading={loading}
        error={error}
        onSelect={onSelect}
        setActiveIndex={setActiveIndex}
      />

      <SearchResult result={result} />

      <TrendingPanel
        items={trending}
        onPick={(q) => {
          setQuery(q);
          setShowDropdown(true);
        }}
      />

      <footer className="footer">Search Typeahead — HLD assignment</footer>
    </div>
  );
}

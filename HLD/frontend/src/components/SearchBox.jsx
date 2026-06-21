import SuggestionList from './SuggestionList.jsx';

/**
 * The search input + button + suggestion dropdown. Presentational: all state lives in App.
 * Renders loading and error states, and forwards keyboard events for navigation.
 */
export default function SearchBox({
  query,
  onChange,
  onKeyDown,
  onFocus,
  onSubmitClick,
  suggestions,
  activeIndex,
  showDropdown,
  loading,
  error,
  onSelect,
  setActiveIndex,
}) {
  const open = showDropdown && query.trim().length > 0;

  return (
    <div className="searchbox">
      <div className="input-row">
        <div className="input-wrap">
          <input
            type="text"
            className="search-input"
            placeholder="Search for anything… (e.g. iphone, java, best laptop)"
            value={query}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={onKeyDown}
            onFocus={onFocus}
            autoComplete="off"
            aria-label="Search"
          />
          {loading && <span className="spinner" aria-label="loading" />}
        </div>
        <button className="search-btn" onClick={onSubmitClick}>
          Search
        </button>
      </div>

      {error && <div className="error">{error}</div>}

      {open && (
        <SuggestionList
          query={query}
          suggestions={suggestions}
          activeIndex={activeIndex}
          onSelect={onSelect}
          setActiveIndex={setActiveIndex}
          loading={loading}
        />
      )}
    </div>
  );
}

/** The dropdown of suggestions. Highlights the active row, bolds the matched prefix,
 *  and shows the count. Empty input is handled upstream; here we just handle "no matches". */
export default function SuggestionList({
  query,
  suggestions,
  activeIndex,
  onSelect,
  setActiveIndex,
  loading,
}) {
  if (suggestions.length === 0) {
    return (
      <ul className="suggestions">
        <li className="empty">{loading ? 'Searching…' : 'No suggestions'}</li>
      </ul>
    );
  }

  const prefixLen = query.trim().length;

  return (
    <ul className="suggestions" role="listbox">
      {suggestions.map((s, i) => (
        <li
          key={s.query}
          role="option"
          aria-selected={i === activeIndex}
          className={i === activeIndex ? 'suggestion active' : 'suggestion'}
          onMouseEnter={() => setActiveIndex(i)}
          onMouseDown={(e) => {
            e.preventDefault(); // keep focus; fire before blur
            onSelect(s);
          }}
        >
          <span className="suggestion-text">
            <strong>{s.query.slice(0, prefixLen)}</strong>
            {s.query.slice(prefixLen)}
          </span>
          <span className="suggestion-count">{s.count.toLocaleString()}</span>
        </li>
      ))}
    </ul>
  );
}

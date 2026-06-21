/** The "Trending searches" section. Items come from GET /trending (recency window). */
export default function TrendingPanel({ items, onPick }) {
  return (
    <section className="trending">
      <h2>Trending</h2>
      {items.length === 0 ? (
        <p className="muted">No recent activity yet. Submit a few searches to see trends.</p>
      ) : (
        <ol className="trending-list">
          {items.map((it) => (
            <li key={it.query}>
              <button className="trending-item" onClick={() => onPick(it.query)}>
                <span>{it.query}</span>
                <span className="trending-score">{Math.round(it.score)}</span>
              </button>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

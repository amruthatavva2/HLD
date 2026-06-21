/** Shows the dummy search response returned by POST /search. */
export default function SearchResult({ result }) {
  if (!result) return null;
  return (
    <div className="search-result">
      <strong>{result.message}</strong> — “{result.query}” recorded. Count updates on the next
      batch flush.
    </div>
  );
}

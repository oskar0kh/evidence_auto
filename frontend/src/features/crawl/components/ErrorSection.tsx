interface ErrorSectionProps {
  errors: { url: string; error: string }[];
}

export default function ErrorSection({ errors }: ErrorSectionProps) {
  if (errors.length === 0) {
    return null;
  }

  return (
    <section className="error-section">
      <h2>실패한 URL</h2>
      <ul>
        {errors.map((item) => (
          <li key={item.url}>
            <strong>{item.url}</strong>: {item.error}
          </li>
        ))}
      </ul>
    </section>
  );
}

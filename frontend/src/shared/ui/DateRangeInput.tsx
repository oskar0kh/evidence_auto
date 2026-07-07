interface DateRangeInputProps {
  startDate: string;
  endDate: string;
  onStartDateChange: (value: string) => void;
  onEndDateChange: (value: string) => void;
  disabled?: boolean;
}

function todayString(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

export default function DateRangeInput({
  startDate,
  endDate,
  onStartDateChange,
  onEndDateChange,
  disabled = false,
}: DateRangeInputProps) {
  const today = todayString();
  const startMax = endDate && endDate < today ? endDate : today;

  return (
    <div className="date-range-row">
      <input
        id="search-start-date"
        className="date-input"
        type="date"
        value={startDate}
        max={startMax}
        onChange={(e) => onStartDateChange(e.target.value)}
        disabled={disabled}
        aria-label="검색 시작일"
      />
      <span className="date-range-separator" aria-hidden="true">
        ~
      </span>
      <input
        id="search-end-date"
        className="date-input"
        type="date"
        value={endDate}
        min={startDate || undefined}
        max={today}
        onChange={(e) => onEndDateChange(e.target.value)}
        disabled={disabled}
        aria-label="검색 종료일"
      />
    </div>
  );
}

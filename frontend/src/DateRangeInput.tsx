interface DateRangeInputProps {
  startDate: string;
  endDate: string;
  onStartDateChange: (value: string) => void;
  onEndDateChange: (value: string) => void;
  disabled?: boolean;
}

export default function DateRangeInput({
  startDate,
  endDate,
  onStartDateChange,
  onEndDateChange,
  disabled = false,
}: DateRangeInputProps) {
  return (
    <div className="date-range-row">
      <input
        id="search-start-date"
        className="date-input"
        type="date"
        value={startDate}
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
        onChange={(e) => onEndDateChange(e.target.value)}
        disabled={disabled}
        aria-label="검색 종료일"
      />
    </div>
  );
}

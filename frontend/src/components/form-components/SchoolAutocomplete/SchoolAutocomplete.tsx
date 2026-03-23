import { useEffect, useMemo, useRef, useState } from "react";

import { CABA_SCHOOLS } from "@/lib/caba-schools";

type SchoolAutocompleteProps = {
  value: string;
  onChange: (value: string) => void;
  label: string;
  placeholder?: string;
};

export function SchoolAutocomplete({
  value,
  onChange,
  label,
  placeholder = "Escribí el colegio",
}: SchoolAutocompleteProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const rootRef = useRef<HTMLLabelElement | null>(null);

  const suggestions = useMemo(() => {
    const term = value.trim().toLowerCase();
    if (!term) {
      return CABA_SCHOOLS.slice(0, 8);
    }

    return CABA_SCHOOLS.filter((school) => school.toLowerCase().includes(term)).slice(0, 8);
  }, [value]);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setIsOpen(false);
        setHighlightedIndex(-1);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const selectSuggestion = (suggestion: string) => {
    onChange(suggestion);
    setIsOpen(false);
    setHighlightedIndex(-1);
  };

  return (
    <label ref={rootRef} style={{ display: "grid", gap: 6, position: "relative" }}>
      <span>{label}</span>
      <input
        type="text"
        value={value}
        placeholder={placeholder}
        onFocus={() => setIsOpen(true)}
        onChange={(event) => {
          onChange(event.target.value);
          setIsOpen(true);
          setHighlightedIndex(-1);
        }}
        onKeyDown={(event) => {
          if (!isOpen && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
            setIsOpen(true);
            return;
          }

          if (event.key === "ArrowDown") {
            event.preventDefault();
            setHighlightedIndex((current) => Math.min(current + 1, suggestions.length - 1));
          }

          if (event.key === "ArrowUp") {
            event.preventDefault();
            setHighlightedIndex((current) => Math.max(current - 1, 0));
          }

          if (event.key === "Enter" && highlightedIndex >= 0 && suggestions[highlightedIndex]) {
            event.preventDefault();
            selectSuggestion(suggestions[highlightedIndex]);
          }

          if (event.key === "Escape") {
            setIsOpen(false);
            setHighlightedIndex(-1);
          }
        }}
        aria-expanded={isOpen}
        aria-autocomplete="list"
        aria-haspopup="listbox"
      />

      {isOpen && suggestions.length > 0 ? (
        <ul
          role="listbox"
          style={{
            position: "absolute",
            top: "100%",
            left: 0,
            right: 0,
            zIndex: 10,
            margin: 4,
            padding: 0,
            listStyle: "none",
            background: "#fff",
            border: "1px solid #d7e0ea",
            borderRadius: 12,
            boxShadow: "0 12px 30px rgba(15, 23, 42, 0.12)",
            maxHeight: 240,
            overflowY: "auto",
          }}
        >
          {suggestions.map((suggestion, index) => (
            <li key={suggestion}>
              <button
                type="button"
                role="option"
                aria-selected={highlightedIndex === index}
                onMouseDown={(event) => {
                  event.preventDefault();
                  selectSuggestion(suggestion);
                }}
                onMouseEnter={() => setHighlightedIndex(index)}
                style={{
                  width: "100%",
                  textAlign: "left",
                  border: "none",
                  background: highlightedIndex === index ? "#edf4fb" : "transparent",
                  padding: "10px 12px",
                  cursor: "pointer",
                }}
              >
                {suggestion}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </label>
  );
}

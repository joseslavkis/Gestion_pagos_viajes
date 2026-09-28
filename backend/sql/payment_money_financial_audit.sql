-- Read-only diagnostic findings. An empty result is clean; investigate each row
-- against persisted history before considering any financial correction.
-- Legacy payment_receipts may also contribute to installment paid_amount, so
-- installment balances are intentionally not compared with allocation totals.
WITH allocation_totals AS (
    SELECT outcome_id, SUM(amount_in_trip_currency) AS trip_total,
           SUM(reported_amount) AS reported_total
    FROM payment_allocations
    GROUP BY outcome_id
)
SELECT 'approved_trip_allocation_mismatch' AS audit_type,
       o.submission_id, o.id AS outcome_id,
       o.amount_in_trip_currency AS recorded_amount,
       COALESCE(a.trip_total, 0) AS expected_amount
FROM payment_outcomes o
LEFT JOIN allocation_totals a ON a.outcome_id = o.id
WHERE o.status = 'APPROVED'
  AND o.amount_in_trip_currency <> COALESCE(a.trip_total, 0)
UNION ALL
SELECT 'approved_reported_allocation_mismatch', o.submission_id, o.id,
       o.reported_amount, COALESCE(a.reported_total, 0)
FROM payment_outcomes o
LEFT JOIN allocation_totals a ON a.outcome_id = o.id
WHERE o.status = 'APPROVED'
  AND o.reported_amount <> COALESCE(a.reported_total, 0)
UNION ALL
-- This is a diagnostic against the persisted v1 rate, not a repricing instruction.
SELECT 'pending_legacy_snapshot_discrepancy', s.id, NULL::bigint,
       s.amount_in_trip_currency,
       CASE WHEN t.currency = 'USD' AND s.payment_currency = 'ARS'
            THEN ROUND(s.reported_amount / s.exchange_rate, 2)
            WHEN t.currency = 'ARS' AND s.payment_currency = 'USD'
            THEN ROUND(s.reported_amount * s.exchange_rate, 2)
       END
FROM payment_submissions s
JOIN trips t ON t.id = s.trip_id
WHERE s.status = 'PENDING' AND s.calculation_version = 'v1'
  AND s.payment_currency <> t.currency AND s.exchange_rate > 0
  AND ((t.currency = 'USD' AND s.payment_currency = 'ARS'
        AND s.amount_in_trip_currency <> ROUND(s.reported_amount / s.exchange_rate, 2))
    OR (t.currency = 'ARS' AND s.payment_currency = 'USD'
        AND s.amount_in_trip_currency <> ROUND(s.reported_amount * s.exchange_rate, 2)))
UNION ALL
SELECT 'incomplete_v2_cross_currency_snapshot', s.id, NULL::bigint,
       s.exchange_rate, NULL::numeric
FROM payment_submissions s
JOIN trips t ON t.id = s.trip_id
WHERE s.calculation_version = '2' AND s.payment_currency <> t.currency
  AND (s.exchange_rate IS NULL OR s.exchange_rate <= 0
       OR s.exchange_rate_scale IS NULL OR s.exchange_rate_provider IS NULL
       OR s.exchange_rate_source IS NULL OR s.exchange_rate_requested_date IS NULL
       OR s.exchange_rate_effective_date IS NULL)
UNION ALL
SELECT 'null_calculation_version', s.id, NULL::bigint, NULL::numeric, NULL::numeric
FROM payment_submissions s
WHERE s.calculation_version IS NULL
UNION ALL
SELECT 'invalid_exchange_rate_scale', s.id, NULL::bigint,
       s.exchange_rate, NULL::numeric
FROM payment_submissions s
WHERE s.exchange_rate_scale IS NOT NULL
  AND (s.exchange_rate_scale NOT BETWEEN 0 AND 8
       OR s.exchange_rate IS NULL
       OR (s.exchange_rate_scale BETWEEN 0 AND 8
           AND s.exchange_rate <> ROUND(s.exchange_rate, s.exchange_rate_scale)))
UNION ALL
SELECT 'same_currency_invented_rate', s.id, NULL::bigint,
       s.exchange_rate, NULL::numeric
FROM payment_submissions s
JOIN trips t ON t.id = s.trip_id
WHERE s.payment_currency = t.currency
  AND (s.exchange_rate IS NOT NULL OR s.exchange_rate_scale IS NOT NULL
       OR s.exchange_rate_provider IS NOT NULL)
ORDER BY audit_type, submission_id, outcome_id;

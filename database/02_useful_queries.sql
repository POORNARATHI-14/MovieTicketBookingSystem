-- Handy queries for MySQL Workbench while demonstrating the project.
USE movie_ticket_booking_db;

-- 1. All seats of a show and their state (change the show id)
SELECT id, seat_number, status, locked_until, locked_by_booking_id FROM seats WHERE show_id = 1 ORDER BY id;

-- 2. Bookings with their Saga progress
SELECT b.id, b.user_id, b.seat_id, b.status AS booking_status, b.failure_reason,
       s.current_step, s.status AS saga_status, b.created_at
FROM bookings b JOIN saga_transactions s ON s.booking_id = b.id
ORDER BY b.id DESC;

-- 3. Payments (kept for audit even when a booking fails)
SELECT id, booking_id, amount, status, method, gateway_reference, failure_reason, idempotency_key FROM payments ORDER BY id DESC;

-- 4. Events waiting for / already sent to RabbitMQ
SELECT id, event_type, aggregate_id, status, attempts, created_at, published_at FROM outbox_events ORDER BY id DESC;

-- 5. Notifications
SELECT id, user_id, type, message, is_read, created_at FROM notifications ORDER BY id DESC;

-- 6. Seats currently held (will expire if not paid)
SELECT id, show_id, seat_number, locked_until, locked_by_booking_id FROM seats WHERE status = 'TEMPORARILY_LOCKED';

-- 7. Sanity check: a seat must never have more than one CONFIRMED booking (must return 0 rows)
SELECT seat_id, COUNT(*) FROM bookings WHERE status = 'CONFIRMED' GROUP BY seat_id HAVING COUNT(*) > 1;

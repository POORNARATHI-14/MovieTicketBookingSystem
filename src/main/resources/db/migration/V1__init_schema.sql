-- =====================================================================
-- Movie Ticket Booking System - initial schema (MySQL 8.0+)
-- Managed by Flyway. Do not edit after it has been applied; add V3, V4...
-- =====================================================================

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE movies (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    title        VARCHAR(150)  NOT NULL,
    genre        VARCHAR(60)   NOT NULL,
    duration_min INT           NOT NULL,
    language     VARCHAR(40)   NOT NULL,
    description  VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_movies_duration CHECK (duration_min > 0),
    INDEX idx_movies_title (title),
    INDEX idx_movies_genre (genre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE theatres (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(120) NOT NULL,
    city          VARCHAR(80)  NOT NULL,
    address       VARCHAR(255) NOT NULL,
    total_screens INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_theatres_screens CHECK (total_screens >= 1),
    INDEX idx_theatres_city (city)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE shows (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    movie_id   BIGINT        NOT NULL,
    theatre_id BIGINT        NOT NULL,
    screen_no  INT           NOT NULL,
    show_time  DATETIME(6)   NOT NULL,
    price      DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_shows_movie   FOREIGN KEY (movie_id)   REFERENCES movies (id),
    CONSTRAINT fk_shows_theatre FOREIGN KEY (theatre_id) REFERENCES theatres (id),
    CONSTRAINT uk_shows_screen_time UNIQUE (theatre_id, screen_no, show_time),
    CONSTRAINT ck_shows_price  CHECK (price > 0),
    CONSTRAINT ck_shows_screen CHECK (screen_no >= 1),
    INDEX idx_shows_movie (movie_id),
    INDEX idx_shows_time (show_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE seats (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    show_id              BIGINT      NOT NULL,
    seat_number          VARCHAR(10) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    locked_until         DATETIME(6) NULL,
    locked_by_booking_id BIGINT      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id) ON DELETE CASCADE,
    CONSTRAINT uk_seats_show_number UNIQUE (show_id, seat_number),
    CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'TEMPORARILY_LOCKED', 'BOOKED')),
    CONSTRAINT ck_seats_lock_expiry CHECK (status <> 'TEMPORARILY_LOCKED' OR locked_until IS NOT NULL),
    INDEX idx_seats_status_lock (status, locked_until),
    INDEX idx_seats_locked_by (locked_by_booking_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE bookings (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    show_id         BIGINT        NOT NULL,
    seat_id         BIGINT        NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    total_amount    DECIMAL(10,2) NOT NULL,
    idempotency_key VARCHAR(64)   NOT NULL,
    request_hash    CHAR(64)      NOT NULL,
    failure_reason  VARCHAR(500)  NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT fk_bookings_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT uk_bookings_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_bookings_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_bookings_amount CHECK (total_amount >= 0),
    INDEX idx_bookings_user (user_id, created_at),
    INDEX idx_bookings_show (show_id),
    INDEX idx_bookings_seat_status (seat_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- seats <-> bookings reference each other, so this FK is added after both exist
ALTER TABLE seats
    ADD CONSTRAINT fk_seats_booking FOREIGN KEY (locked_by_booking_id)
        REFERENCES bookings (id) ON DELETE SET NULL;

CREATE TABLE payments (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    booking_id        BIGINT        NOT NULL,
    amount            DECIMAL(10,2) NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    method            VARCHAR(30)   NOT NULL,
    idempotency_key   VARCHAR(100)  NOT NULL,
    request_hash      CHAR(64)      NOT NULL,
    gateway_reference VARCHAR(60)   NULL,
    failure_reason    VARCHAR(500)  NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- payments are audit records: no ON DELETE CASCADE, a booking with payments cannot be deleted
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uk_payments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0),
    INDEX idx_payments_booking (booking_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    is_read    TINYINT(1)   NOT NULL DEFAULT 0, -- "read" is a reserved word in MySQL
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_notifications_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE saga_transactions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    booking_id     BIGINT       NOT NULL,
    current_step   VARCHAR(30)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    failure_reason VARCHAR(500) NULL,
    started_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_saga_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uk_saga_booking UNIQUE (booking_id),
    CONSTRAINT ck_saga_status CHECK (status IN ('IN_PROGRESS', 'AWAITING_PAYMENT', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    INDEX idx_saga_status_updated (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_events (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    event_type   VARCHAR(50) NOT NULL,
    aggregate_id BIGINT      NOT NULL,
    payload      JSON        NOT NULL,
    status       VARCHAR(15) NOT NULL DEFAULT 'PENDING',
    attempts     INT         NOT NULL DEFAULT 0,
    last_error   VARCHAR(500) NULL,
    created_at   DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    INDEX idx_outbox_status_id (status, id),
    INDEX idx_outbox_aggregate (aggregate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

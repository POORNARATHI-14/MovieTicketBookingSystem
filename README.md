# Movie Ticket Booking System
**Spring Boot 3.5 · Java 25 · MySQL 8 · Saga pattern · Transactional outbox · RabbitMQ (optional)**

Browse movies, theatres and shows → see seats → book one → simulated payment → confirmation.
The core of the project is that **two people can never book the same seat**, retries never double-book or
double-charge, unpaid holds expire, and every failure is rolled back by a *compensating action*.

> **Architecture, stated accurately:** this is ONE Spring Boot application (a *modular monolith*), organised in
> separate packages (`user`, `movie`, `theatre`, `show`, `seat`, `booking`, `payment`, `notification`, `events`,
> `saga`). It imitates microservice-style separation and Saga orchestration, but it is **not** a set of
> independently deployable microservices.
> **Payments are simulated.** No real gateway, card or money is involved anywhere.

---------------------------------------------------------------------------------------------------

## 1. What YOU have to do manually (Windows 11) — checklist

| # | Step | Command / action |
|---|------|------------------|
| 1 | Install **JDK 25** (Eclipse Temurin), **Maven 3.9+**, **MySQL Server 8.x + Workbench**, **Postman** | check below |
| 2 | **Create the databases** in MySQL | Workbench → open `database\01_create_database.sql` → ⚡ Run |
| 3 | **Create your settings file** | copy `scripts\set-env.example.ps1` → `scripts\set-env.ps1`, edit 3 values |
| 4 | **Run the tests** (optional but recommended) | `.\scripts\test.ps1` |
| 5 | **Start the app** | `.\scripts\run.ps1` |
| 6 | **Import Postman collection** | `postman\MovieTicketBookingSystem.postman_collection.json` |
| 7 | *(optional)* RabbitMQ | see section 7 |

### Step 1 – check your tools
Open **PowerShell** and run:
```powershell
java -version      # must say 25 (Temurin)
mvn -v             # Maven 3.9+, and "Java version: 25"
mysql --version    # MySQL 8.x
```
If `mvn -v` shows an older Java, set `JAVA_HOME` to your JDK 25 folder
(e.g. `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-25..."`) and open a NEW PowerShell window.

### Step 2 – create the databases (one time)
**Option A – MySQL Workbench:** File → Open SQL Script → `database\01_create_database.sql` → click the ⚡ button.
**Option B – command line:**
```powershell
mysql -u root -p < database\01_create_database.sql
```
This creates `movie_ticket_booking_db` (the app) and `movie_ticket_booking_test_db` (used only by `mvn test`).
**You do not create tables by hand** – Flyway creates all 10 tables automatically on the first start
(`src\main\resources\db\migration\V1__init_schema.sql`, plus a few demo movies/theatres in `V2`).

### Step 3 – your settings (no secrets live in the code)
```powershell
Copy-Item scripts\set-env.example.ps1 scripts\set-env.ps1
notepad scripts\set-env.ps1
```
Fill in:

| Variable | Meaning |
|---|---|
| `DB_PASSWORD` | your MySQL password (`DB_USERNAME` defaults to `root`) |
| `JWT_SECRET` | any random text, **32+ characters**. Generate one: `-join ((48..57)+(65..90)+(97..122) \| Get-Random -Count 40 \| % {[char]$_})` |
| `ADMIN_PASSWORD` | password of the admin account that will be created at start-up (needed to add movies/theatres/shows) |

`scripts\set-env.ps1` is in `.gitignore`, so it never gets committed.

If PowerShell refuses to run scripts: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`
(affects only the current window).

### Step 4 – run the tests
```powershell
.\scripts\test.ps1          # = mvn clean test, against the REAL MySQL test database
```
The first run downloads dependencies (a few minutes). Details in section 8.

### Step 5 – start the application
```powershell
.\scripts\run.ps1           # = mvn spring-boot:run
```
Wait for `Started MovieTicketBookingApplication`, then the API is at **http://localhost:8080**.
Check: open http://localhost:8080/api/movies in a browser – you should see the 4 demo movies.

Prefer typing the commands yourself?
```powershell
. .\scripts\set-env.ps1
mvn spring-boot:run
# or build a jar:
mvn clean package -DskipTests
java -jar target\movie-ticket-booking-system-1.0.0.jar
```

### Step 6 – Postman
Import `postman\MovieTicketBookingSystem.postman_collection.json`. In the collection's **Variables** tab set
`adminPassword` to the same value as your `ADMIN_PASSWORD`. Then run folders **1 → 4 in order**
(right-click the collection → *Run collection*). Tokens and ids are captured automatically; every request has a test.

### VS Code
Install *Extension Pack for Java* and *Spring Boot Extension Pack*, then **File → Open Folder → MovieTicketBookingSystem**.
Start from the integrated terminal (it must be the PowerShell where you ran `. .\scripts\set-env.ps1`).

---------------------------------------------------------------------------------------------------

## 2. How the booking works

```
POST /api/bookings  (header Idempotency-Key)
 1 create PENDING booking + saga row                         ┐ each step = its own short
 2 lock seat   AVAILABLE -> TEMPORARILY_LOCKED (atomic)      │ MySQL transaction that also
 3 charge payment (simulated, idempotent)                    │ writes saga progress and an
 4 confirm seat -> BOOKED, booking CONFIRMED, notify         ┘ outbox event
 any failure -> compensation: booking CANCELLED, refund if captured, seat released, notify
```
Diagrams (Mermaid, validated): `docs\*.mmd` – architecture, ER, seat state machine, race sequence, Saga success/failure.

### Why two users cannot book the same seat
Seat changes are never "read, check in Java, save". They are single conditional statements:
```sql
UPDATE seats SET status='TEMPORARILY_LOCKED', locked_until=?, locked_by_booking_id=?
 WHERE id=? AND status='AVAILABLE';          -- 1 row affected = you won, 0 = HTTP 409
UPDATE seats SET status='BOOKED' WHERE id=? AND status='TEMPORARILY_LOCKED'
   AND locked_by_booking_id=? AND locked_until > now;   -- an expired hold can never become BOOKED
```
MySQL's row lock makes concurrent callers take turns, so exactly one `UPDATE` matches.

### Other guarantees
* **Idempotency** – bookings and payments have `UNIQUE idempotency_key` + a stored request hash. Same key + same request
  → original result (HTTP 200); same key + different request → 422; simultaneous duplicates → the unique index lets one
  through, the others read and return the winner's result.
* **Timeouts** – `SeatLockExpiryJob` (every 5 s) releases holds older than `SEAT_LOCK_MINUTES` (default 5), cancelling the
  pending booking. A payment that arrives after the hold expired is **refunded** and the seat is never booked.
* **Crash recovery** – `SagaRecoveryJob` finishes or rolls back sagas left `IN_PROGRESS` (e.g. app killed mid-booking).
* **Outbox** – every event is inserted into `outbox_events` in the same transaction as the change. With RabbitMQ enabled,
  `OutboxRelay` publishes pending rows (publisher confirms, `SKIP LOCKED`, at-least-once). If RabbitMQ is off or down,
  events simply wait in the table – nothing is lost and the app still works.
* **Security** – BCrypt password hashes, JWT bearer tokens, roles `USER` / `ADMIN`. Browsing is public; creating
  movies/theatres/shows needs ADMIN; bookings, payments and notifications are private to their owner.
  Seat lock/confirm/release are **not** REST endpoints – only the Saga can call them.

### Simulated payment "test cards" (`paymentMethod`)
| Value | Result | Booking outcome |
|---|---|---|
| `TEST_SUCCESS` (default) | approved | 201 CONFIRMED |
| `TEST_DECLINED` | declined | 402, CANCELLED, seat released, FAILED payment kept |
| `TEST_TIMEOUT` | gateway timeout | 402, CANCELLED, seat released |
| `TEST_CAPTURE_THEN_FAIL` | captured, then a later step fails | 409, payment **REFUNDED**, CANCELLED, seat released |
| `TEST_RANDOM` | approved with probability `success-rate` (0.8) | depends |

`autoPay:false` on `POST /api/bookings` only holds the seat (202 PENDING); pay later with `POST /api/payments` before it expires.

---------------------------------------------------------------------------------------------------

## 3. REST API

| Method | Endpoint | Auth | Purpose |
|---|---|---|---|
| POST | `/api/users/register` | – | register (BCrypt) |
| POST | `/api/users/login` | – | returns JWT |
| GET | `/api/users/me` | user | current user |
| GET / POST | `/api/movies` | GET public, POST admin | list (`?genre=&language=&title=`) / create |
| GET / PUT / DELETE | `/api/movies/{id}` | GET public, others admin | |
| GET / POST | `/api/theatres` (`?city=`), GET `/api/theatres/{id}` | GET public, POST admin | |
| GET / POST | `/api/shows` (`?movieId=&theatreId=&date=`) | GET public, POST admin | POST also generates the seats |
| GET | `/api/shows/{id}` | public | show details + free seats |
| GET | `/api/shows/{showId}/seats` | public | seat map and statuses |
| POST | `/api/shows/{showId}/seats` | admin | add extra seats |
| POST | `/api/bookings` | user + `Idempotency-Key` header | **execute the booking Saga** |
| GET | `/api/bookings/{id}` | owner/admin | booking + saga status |
| GET | `/api/bookings?userId={id}` | owner/admin | history (default: your own) |
| POST | `/api/bookings/{id}/cancel` | owner/admin | cancel (refunds if confirmed) |
| POST | `/api/payments` | user | pay a held booking (idempotent) |
| GET | `/api/payments/{id}` | owner/admin | payment details |
| GET | `/api/notifications/{userId}` | owner/admin | notifications |
| PATCH | `/api/notifications/{id}/read` | owner/admin | mark read |

Status codes: 200 OK/replay · 201 created · 202 held (pending) · 400 validation · 401 no/invalid token · 402 payment failed ·
403 not yours · 404 not found · 409 conflict (seat taken, duplicate…) · 422 idempotency key reused differently.
Every error has the same JSON shape and never contains a stack trace.

### Sample requests / responses
**Register** `POST /api/users/register`
```json
{ "name": "Asha Kumar", "email": "asha@example.com", "password": "Secret123" }
```
```json
{ "id": 1, "name": "Asha Kumar", "email": "asha@example.com", "role": "USER", "createdAt": "2026-10-06T10:15:30.123456" }
```
**Login** `POST /api/users/login` → `{ "token": "eyJ...", "tokenType": "Bearer", "expiresInSeconds": 3600, "user": { ... } }`

**Create show (admin)** `POST /api/shows`
```json
{ "movieId": 1, "theatreId": 1, "screenNo": 1, "showTime": "2026-10-10T18:30:00", "price": 150.00, "rows": 5, "seatsPerRow": 10 }
```
**Book a seat** `POST /api/bookings` — headers `Authorization: Bearer <token>`, `Idempotency-Key: 3f1c9a7e-0b1d-4c55-9a40-2d1f6f0a8b11`
```json
{ "showId": 1, "seatId": 3, "paymentMethod": "TEST_SUCCESS" }
```
→ `201 Created`
```json
{ "id": 7, "userId": 1, "showId": 1, "seatId": 3, "seatNumber": "A3", "status": "CONFIRMED", "totalAmount": 150.00,
  "idempotencyKey": "3f1c9a7e-0b1d-4c55-9a40-2d1f6f0a8b11", "sagaStep": "BOOKING_CONFIRMED", "sagaStatus": "COMPLETED",
  "createdAt": "2026-10-06T10:20:00.100000", "updatedAt": "2026-10-06T10:20:00.450000" }
```
Retry with the same key → `200 OK`, same body. Someone else wants seat 3 → `409`:
```json
{ "timestamp": "2026-10-06T10:20:01", "status": 409, "error": "Conflict", "code": "SEAT_UNAVAILABLE",
  "message": "Seat 3 is already booked or being held by another customer", "path": "/api/bookings" }
```
Payment declined (`"paymentMethod": "TEST_DECLINED"`) → `402` with `"status": "CANCELLED"`, `"failureReason": "PAYMENT_FAILED: DECLINED: Card declined (simulated)"`.

**Pay a held booking** `POST /api/payments`
```json
{ "bookingId": 8, "amount": 150.00, "paymentMethod": "TEST_SUCCESS", "idempotencyKey": "pay-8-attempt-1" }
```
→ `201` `{ "id": 5, "bookingId": 8, "status": "COMPLETED", "bookingStatus": "CONFIRMED", "gatewayReference": "SIM-1a2b3c4d5e6f", ... }`

---------------------------------------------------------------------------------------------------

## 4. Database
10 tables (`users, movies, theatres, shows, seats, bookings, payments, notifications, saga_transactions, outbox_events`)
with foreign keys, indexes and constraints: unique email, unique `(show_id, seat_number)`, unique booking and payment
idempotency keys, `CHECK` constraints against negative prices/amounts, a `CHECK` that a locked seat always has an expiry,
and no `ON DELETE CASCADE` on payments (audit history survives). Passwords are BCrypt hashes.
ER diagram: `docs\er-diagram.mmd`. Handy demo queries: `database\02_useful_queries.sql`.
Deliberate deviation: the notification column is `is_read` (not `read`) because `READ` is a reserved word in MySQL.
Extra columns beyond the brief: `users.role`, `request_hash`, `failure_reason`, `updated_at`, payment `method`/`gateway_reference`, outbox `attempts`/`published_at`.

## 5. Project layout
```
MovieTicketBookingSystem/
├── pom.xml  README.md  .gitignore
├── database/        01_create_database.sql  02_useful_queries.sql
├── scripts/         set-env.example.ps1  run.ps1  test.ps1
├── postman/         MovieTicketBookingSystem.postman_collection.json
├── docs/            *.mmd (Mermaid diagrams)
└── src/
    ├── main/java/com/movieticket/
    │   ├── MovieTicketBookingApplication.java
    │   ├── config/ common/{exception,response,validation} security/
    │   ├── user/ movie/ theatre/ show/ seat/ booking/ payment/ notification/ events/ saga/
    └── main/resources/  application.properties  db/migration/V1__init_schema.sql V2__seed_demo_catalog.sql
    └── test/java/com/movieticket/  (user, catalog, booking, concurrency, unit, support)
```

## 6. Configuration reference (environment variables)
| Variable | Default | |
|---|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` | localhost / 3306 / movie_ticket_booking_db / root | |
| `DB_PASSWORD` | **required** | |
| `JWT_SECRET` | **required**, ≥ 32 chars | |
| `JWT_EXPIRATION_MINUTES` | 60 | |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | admin@movieticket.local / *(none: no admin created)* | |
| `SEAT_LOCK_MINUTES` | 5 | how long an unpaid seat hold lasts |
| `PAYMENT_DEFAULT_METHOD` | TEST_SUCCESS | |
| `RABBITMQ_ENABLED` | false | `RABBITMQ_HOST/PORT/USERNAME/PASSWORD` default localhost/5672/guest/guest |
| `SERVER_PORT` | 8080 | |
| `TEST_DB_NAME` | movie_ticket_booking_test_db | used by `mvn test` |

## 7. RabbitMQ (optional)
The app starts and works without it. To see events flow end to end:
1. Install **Erlang/OTP**, then **RabbitMQ Server** for Windows (rabbitmq.com → Install → Windows). It runs as a service.
2. Enable the web UI: open *RabbitMQ Command Prompt (sbin dir)* and run `rabbitmq-plugins enable rabbitmq_management`.
3. In `scripts\set-env.ps1` set `$env:RABBITMQ_ENABLED = "true"`, restart the app.
4. Open http://localhost:15672 (guest / guest) → *Queues* → `movieticket.events.audit`. Make a booking: the app log shows
   `[RabbitMQ consumer] booking.created …`, `seat.locked`, `payment.completed`, `booking.confirmed`.
Check delivery in MySQL: `SELECT status, COUNT(*) FROM outbox_events GROUP BY status;` (PENDING → PUBLISHED).
Exchange `movieticket.events` (topic) · routing keys `booking.created, seat.locked, payment.completed, payment.failed,
payment.refunded, seat.released, booking.confirmed, booking.cancelled`.

## 8. Tests
`mvn clean test` (or `.\scripts\test.ps1`) runs **real MySQL** tests (no H2) in `movie_ticket_booking_test_db`; each test starts from empty tables.

| Required scenario | Test |
|---|---|
| Register valid user → saved in MySQL | `UserApiIT.registerValidUser` |
| Create movie and show → relationships stored | `CatalogIT.createMovieTheatreShow` |
| Book available seat → CONFIRMED | `BookingSagaIT.bookAvailableSeat` |
| Payment failure → CANCELLED, seat released | `BookingSagaIT.paymentFailureCompensates` |
| Already booked seat rejected | `BookingSagaIT.alreadyBookedSeat` |
| Two+ users, same seat, concurrently → at most one | `ConcurrentBookingIT.twelveUsersOneSeat` |
| Retry booking with same key → original result | `IdempotencyIT.bookingRetryReturnsOriginal` |
| Retry payment with same key → no duplicate | `IdempotencyIT.paymentRetry`, `ConcurrentBookingIT.simultaneousDuplicatePayments` |
| Seat lock expires → AVAILABLE | `SeatLockExpiryIT.expiredLockIsReleased` |
| Restart application → data persists | `PersistenceAfterRestartIT` (boots a 2nd application context) |
| Also covered | refund compensation, expiry-vs-payment race, crash recovery, invalid ids, ownership/security, outbox relay (mocked broker), simulator, JWT, validation |

## 9. Implemented features and honest limitations
**Implemented:** everything above, plus cancellation with refund, booking history, notifications, admin seat
creation, filters, and a consistent error format.

**Limitations (by design or scope):**
* Modular monolith, not separately deployable microservices.
* Payment gateway and refunds are simulated; notifications are database records (no e-mail/SMS).
* One seat per booking (matches the `bookings.seat_id` schema).
* Outbox delivery is at-least-once (consumers must tolerate duplicates; each message has a unique `eventId`).
* Times use the server's local time zone; there is no multi-time-zone support.
* No refresh tokens / password reset; no frontend (backend + Postman first, as requested).
* `TEST_CAPTURE_THEN_FAIL` is a deliberate fault-injection switch for demos; set
  `app.payment.simulator.fault-injection-enabled=false` outside demos.

## 10. Troubleshooting
| Symptom | Fix |
|---|---|
| `Could not resolve placeholder 'DB_PASSWORD'` / `'JWT_SECRET'` | you did not load the env file in THIS PowerShell: `. .\scripts\set-env.ps1` |
| `Access denied for user 'root'` | wrong `DB_PASSWORD` |
| `Unknown database` / connection refused | run step 2; make sure the MySQL80 service is running (`services.msc`) |
| `JWT_SECRET must be ... at least 32 characters` | use a longer secret |
| `release version 25 not supported` | Maven is using an older JDK: fix `JAVA_HOME`, reopen PowerShell |
| `Port 8080 was already in use` | `$env:SERVER_PORT="8081"` (and change `baseUrl` in Postman) |
| Admin login fails in Postman | `adminPassword` variable ≠ `ADMIN_PASSWORD` used at start |
| Tests: *Missing DB_PASSWORD* | load the env file first (or use `.\scripts\test.ps1`) |

## 11. What was verified before this ZIP was delivered (and what was NOT)
Be aware of the difference — nothing below is claimed beyond what was actually run.

**Verified in the build environment (real MySQL 8.0.46):**
* `V1__init_schema.sql` and `V2__seed_demo_catalog.sql` apply cleanly to an empty database.
* The exact atomic seat-lock `UPDATE` statements used by `SeatRepository`: with **30 simultaneous connections trying to lock one seat,
  exactly 1 succeeded and 29 got 0 rows**; confirm is refused for the wrong booking and for an expired lock; expiry release works
  (including a lock with no holder); duplicate idempotency key, negative price and "locked seat without expiry" are rejected by constraints;
  the outbox claim query (`FOR UPDATE SKIP LOCKED`) works with a JSON payload column.
* All 6 Mermaid diagrams pass the Mermaid parser; the Postman collection is valid JSON (41 requests).
* All 82 main + 11 test Java files were run through `javac`: **no syntax errors, and no unresolved reference to any class or
  method of this project** (the only unresolved symbols were Spring/Jakarta/JWT library types).

**NOT verified here – you will do this on your machine:**
* `mvn compile` / `mvn test` were **not run**: the build sandbox could not download Maven Central dependencies
  (Spring Boot, Hibernate, …), and has Java 21, not Java 25. So the Spring wiring, JPA mappings, JWT filter and the Java test suite
  have not been executed. They were written carefully and reviewed, but **please run `.\scripts\test.ps1` first**.
  If anything fails, send me the first error message and I will fix it.
* RabbitMQ publishing/consuming was not run (the relay logic is unit-tested with a mocked broker only).
* Not tested on Windows 11 itself.

-- =====================================================================
-- Run ONCE in MySQL Workbench (or the mysql command line) before the first start.
-- The application also tries "createDatabaseIfNotExist=true", but creating the
-- databases yourself is the clean, explicit way and works without CREATE rights
-- for the application user.
-- =====================================================================

-- Main application database
CREATE DATABASE IF NOT EXISTS movie_ticket_booking_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- Separate database used ONLY by "mvn test" (the tests empty their tables, so never share it with real data)
CREATE DATABASE IF NOT EXISTS movie_ticket_booking_test_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- OPTIONAL: a dedicated MySQL user instead of root.
-- Remove the leading "-- " from the 3 statements below, replace the password,
-- then put the same user/password into DB_USERNAME / DB_PASSWORD.
-- ---------------------------------------------------------------------
-- CREATE USER IF NOT EXISTS 'movieticket'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_OWN_PASSWORD';
-- GRANT ALL PRIVILEGES ON movie_ticket_booking_db.*      TO 'movieticket'@'localhost';
-- GRANT ALL PRIVILEGES ON movie_ticket_booking_test_db.* TO 'movieticket'@'localhost';

SHOW DATABASES LIKE 'movie_ticket%';

-- Demo catalogue so the API has something to browse straight after first start.
-- Shows are NOT seeded because every show needs seats; create them with POST /api/shows (see Postman collection).
INSERT INTO movies (title, genre, duration_min, language, description) VALUES
 ('Interstellar',      'Sci-Fi',  169, 'English', 'A team of explorers travels through a wormhole in search of a new home for humanity.'),
 ('The Dark Knight',   'Action',  152, 'English', 'Batman faces the Joker in Gotham City.'),
 ('3 Idiots',          'Comedy',  170, 'Hindi',   'Three engineering students and a friendship that changes how they see education.'),
 ('Vikram',            'Action',  174, 'Tamil',   'A black-ops squad hunts a masked gang behind a string of murders.');

INSERT INTO theatres (name, city, address, total_screens) VALUES
 ('Grand Cinemas',   'Chennai',    '12 Anna Salai, Chennai',          4),
 ('Pearl Multiplex', 'Thoothukudi','45 Beach Road, Thoothukudi',      3),
 ('Metro Screens',   'Madurai',    '8 West Masi Street, Madurai',     5);

CREATE TABLE IF NOT EXISTS pending_trip_students (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    student_dni VARCHAR(8) NOT NULL,
    CONSTRAINT fk_pending_trip_students_trip
        FOREIGN KEY (trip_id)
        REFERENCES trips (id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pending_trip_students_trip_dni
    ON pending_trip_students (trip_id, student_dni);

CREATE INDEX IF NOT EXISTS idx_pending_trip_students_trip_id
    ON pending_trip_students (trip_id);

CREATE INDEX IF NOT EXISTS idx_pending_trip_students_student_dni
    ON pending_trip_students (student_dni);

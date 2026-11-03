-- Minimal test schema only; this is not a reconstruction of the production DB.
CREATE TABLE content_schedule (
    schedule_id INT PRIMARY KEY,
    current_ticket INT NOT NULL,
    total_ticket INT NOT NULL
);

CREATE TABLE reservation (
    reservation_id INT AUTO_INCREMENT PRIMARY KEY,
    reservation_code VARCHAR(100),
    reserved_for_at VARCHAR(30),
    time_zone VARCHAR(50),
    reservation_status VARCHAR(30),
    total_price INT,
    adult_qty INT,
    child_qty INT,
    teen_qty INT,
    user_id INT,
    content_id INT,
    reservation_type VARCHAR(30),
    receiver_name VARCHAR(100),
    receiver_birth VARCHAR(30),
    receiver_phone VARCHAR(30),
    reserve_visit BOOLEAN,
    receive_type VARCHAR(30),
    created_at TIMESTAMP,
    schedule_id INT
);

CREATE TABLE payment (
    payment_id INT AUTO_INCREMENT PRIMARY KEY,
    payment_method VARCHAR(30) NOT NULL,
    payment_status VARCHAR(30),
    amount_price INT,
    paid_at TIMESTAMP,
    reservation_id INT NOT NULL REFERENCES reservation(reservation_id),
    refund_status VARCHAR(30),
    payment_key VARCHAR(100),
    order_key VARCHAR(100)
);

INSERT INTO content_schedule VALUES (1, 10, 10);

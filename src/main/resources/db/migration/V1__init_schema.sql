CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(512) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE questions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_text TEXT NOT NULL,
    option_a VARCHAR(512) NOT NULL,
    option_b VARCHAR(512) NOT NULL,
    option_c VARCHAR(512) NOT NULL,
    option_d VARCHAR(512) NOT NULL,
    correct_option VARCHAR(1) NOT NULL,
    explanation TEXT,
    image_url VARCHAR(1024),
    image_public_id VARCHAR(512),
    category VARCHAR(100),
    difficulty VARCHAR(20) DEFAULT 'MEDIUM',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE test_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    score INT NOT NULL DEFAULT 0,
    total_questions INT NOT NULL,
    correct_answers INT NOT NULL DEFAULT 0,
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    time_taken_seconds INT,
    CONSTRAINT fk_ta_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE attempt_answers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option VARCHAR(1),
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_aa_attempt  FOREIGN KEY (attempt_id)  REFERENCES test_attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_aa_question FOREIGN KEY (question_id) REFERENCES questions(id)     ON DELETE CASCADE
);

CREATE TABLE pdf_uploads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_filename VARCHAR(512) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    questions_extracted INT DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_pu_user FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

CREATE INDEX idx_questions_active    ON questions(active);
CREATE INDEX idx_attempts_user       ON test_attempts(user_id);
CREATE INDEX idx_answers_attempt     ON attempt_answers(attempt_id);
CREATE INDEX idx_refresh_token_user  ON refresh_tokens(user_id);

-- Default admin  password = Admin@123
INSERT INTO users (email, password, full_name, role)
VALUES ('admin@drivingtheory.com',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/lNGWBzK8G',
        'System Admin', 'ADMIN');

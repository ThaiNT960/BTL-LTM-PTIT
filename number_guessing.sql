DROP DATABASE IF EXISTS number_guessing;

CREATE DATABASE number_guessing
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE number_guessing;

-- Tài khoản người dùng
CREATE TABLE users (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL UNIQUE,
    display_name  VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          ENUM('PLAYER', 'ADMIN') NOT NULL DEFAULT 'PLAYER',
    blocked       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Thông tin trận đấu
CREATE TABLE matches (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    player1_id  BIGINT NOT NULL,
    player2_id  BIGINT NOT NULL,
    range_max   INT NOT NULL,
    status      ENUM('ACTIVE', 'FINISHED', 'ABORTED') NOT NULL DEFAULT 'ACTIVE',
    winner_id   BIGINT NULL,
    end_reason  VARCHAR(40),
    started_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at    TIMESTAMP NULL,
    FOREIGN KEY (player1_id) REFERENCES users(id),
    FOREIGN KEY (player2_id) REFERENCES users(id),
    FOREIGN KEY (winner_id)  REFERENCES users(id),
    CHECK (player1_id <> player2_id),
    CHECK (range_max IN (100, 1000, 10000)),
    CHECK (winner_id IS NULL OR winner_id IN (player1_id, player2_id)),
    INDEX idx_matches_player1 (player1_id, status),
    INDEX idx_matches_player2 (player2_id, status)
) ENGINE=InnoDB;

-- Lịch sử các lượt đoán
CREATE TABLE guesses (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id    BIGINT NOT NULL,
    turn_no     INT NOT NULL,
    player_id   BIGINT NOT NULL,
    number      INT NULL,
    hint        ENUM('HIGHER', 'LOWER', 'EXACT', 'TIMEOUT') NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_id, turn_no),
    FOREIGN KEY (match_id)  REFERENCES matches(id),
    FOREIGN KEY (player_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- Tin nhắn trong trận
CREATE TABLE chat_messages (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id    BIGINT NOT NULL,
    sender_id   BIGINT NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (match_id)  REFERENCES matches(id),
    FOREIGN KEY (sender_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- Nhật ký quản trị
CREATE TABLE admin_logs (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id    BIGINT NOT NULL,
    target_id   BIGINT NOT NULL,
    action      VARCHAR(40) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (admin_id)  REFERENCES users(id),
    FOREIGN KEY (target_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- Tài khoản mẫu, mật khẩu chung: 123456
INSERT INTO users (username, display_name, password_hash, role)
VALUES
    ('user1', 'Người chơi 1',
     '210000:mXz4CAv+E76WlJMw3UDhOQ==:CfATXiKWZmBFCFye/hipXqVd3DIv23tA3Woz4rMcjBI=',
     'PLAYER'),

    ('user2', 'Người chơi 2',
     '210000:A9Vl9RFOhC7WSycc8cNFCg==:LIPg+IN+WS66cPJwS2SJudC2bpYvtSGkHOPMVqkeQiM=',
     'PLAYER'),

    ('admin', 'Quản trị viên',
     '210000:qtR4JW96PKGvQv1ZelN4IA==:+M5MZyf0TpCtkLRw2f2yle7TSVbJqChviZRa2zVJw0M=',
     'ADMIN');

-- Số bí mật chỉ được lưu tạm trên server

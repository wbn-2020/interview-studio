-- 服务器发布时显式执行；个人显示偏好与复习排程设置保持独立。
CREATE TABLE user_preference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    theme VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    font_size INT NOT NULL,
    content_width INT NOT NULL,
    motion VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    save_recovery_notice BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_user_preference_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_user_preference_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_user_preference_theme CHECK (theme IN ('LIGHT','DARK','SYSTEM')),
    CONSTRAINT ck_user_preference_font CHECK (font_size BETWEEN 12 AND 24),
    CONSTRAINT ck_user_preference_width CHECK (content_width BETWEEN 720 AND 1600),
    CONSTRAINT ck_user_preference_motion CHECK (motion IN ('SYSTEM','REDUCED','FULL')),
    CONSTRAINT ck_user_preference_zone CHECK (CHAR_LENGTH(time_zone) BETWEEN 1 AND 64),
    CONSTRAINT ck_user_preference_notice CHECK (save_recovery_notice IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

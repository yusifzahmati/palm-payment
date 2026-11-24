-- Baseline schema, matching the PalmInfo and PalmUser entities.
-- IF NOT EXISTS keeps this safe on databases previously created by
-- Hibernate's ddl-auto=update.

CREATE TABLE IF NOT EXISTS palm_info (
    id                 BIGSERIAL PRIMARY KEY,
    palm_type          INTEGER,
    ir_feature_size    INTEGER,
    rgb_feature_size   INTEGER,
    ir_feature_base64  TEXT,
    rgb_feature_base64 TEXT
);

CREATE TABLE IF NOT EXISTS palm_user (
    id          BIGSERIAL PRIMARY KEY,
    full_name   VARCHAR(255),
    fin_code    VARCHAR(255) NOT NULL,
    customer_no VARCHAR(255),
    palm_id     BIGINT       NOT NULL,
    CONSTRAINT uk_palm_user_fin_code UNIQUE (fin_code),
    CONSTRAINT uk_palm_user_palm_id  UNIQUE (palm_id),
    CONSTRAINT fk_palm_user_palm_info FOREIGN KEY (palm_id) REFERENCES palm_info (id)
);

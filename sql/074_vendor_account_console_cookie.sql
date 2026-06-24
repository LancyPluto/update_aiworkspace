ALTER TABLE model_vendor_accounts
    ADD COLUMN console_cookie TEXT NULL AFTER balance_url,
    ADD COLUMN console_cookie_status VARCHAR(20) NULL DEFAULT 'UNKNOWN' AFTER console_cookie;

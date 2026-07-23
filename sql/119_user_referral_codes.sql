SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS ensure_user_referral_codes;

DELIMITER $$
CREATE PROCEDURE ensure_user_referral_codes()
BEGIN
  DECLARE target_user_id BIGINT DEFAULT NULL;
  DECLARE candidate_code CHAR(6) DEFAULT NULL;
  DECLARE allocation_attempts INT DEFAULT 0;
  DECLARE invalid_code_count BIGINT DEFAULT 0;
  DECLARE code_alphabet VARCHAR(32) DEFAULT '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'referral_code'
  ) THEN
    ALTER TABLE users ADD COLUMN referral_code CHAR(6) NULL AFTER public_code;
  END IF;

  SELECT COUNT(*)
  INTO invalid_code_count
  FROM users
  WHERE referral_code IS NOT NULL
    AND (
      CHAR_LENGTH(referral_code) <> 6
      OR referral_code REGEXP '[^2-9A-HJ-NP-Z]'
      OR referral_code NOT REGEXP '[2-9]'
      OR referral_code NOT REGEXP '[A-HJ-NP-Z]'
    );

  IF invalid_code_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'referral code migration blocked: invalid existing code';
  END IF;

  WHILE EXISTS (SELECT 1 FROM users WHERE referral_code IS NULL) DO
    SELECT id
    INTO target_user_id
    FROM users
    WHERE referral_code IS NULL
    ORDER BY id
    LIMIT 1;

    SET allocation_attempts = 0;
    SET candidate_code = NULL;
    WHILE candidate_code IS NULL AND allocation_attempts < 1000 DO
      SET candidate_code = CONCAT(
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1),
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1),
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1),
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1),
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1),
        SUBSTRING(code_alphabet, FLOOR(1 + RAND() * CHAR_LENGTH(code_alphabet)), 1)
      );
      IF candidate_code NOT REGEXP '[2-9]'
        OR candidate_code NOT REGEXP '[A-HJ-NP-Z]'
        OR EXISTS (
          SELECT 1
          FROM users
          WHERE referral_code COLLATE utf8mb4_unicode_ci
            = candidate_code COLLATE utf8mb4_unicode_ci
        ) THEN
        SET candidate_code = NULL;
      END IF;
      SET allocation_attempts = allocation_attempts + 1;
    END WHILE;

    IF candidate_code IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'referral code migration blocked: code allocation failed';
    END IF;

    UPDATE users
    SET referral_code = candidate_code
    WHERE id = target_user_id
      AND referral_code IS NULL;
  END WHILE;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND index_name = 'uk_users_referral_code'
  ) THEN
    CREATE UNIQUE INDEX uk_users_referral_code ON users(referral_code);
  END IF;

  ALTER TABLE users MODIFY COLUMN referral_code CHAR(6) NOT NULL;
END $$
DELIMITER ;

CALL ensure_user_referral_codes();
DROP PROCEDURE IF EXISTS ensure_user_referral_codes;

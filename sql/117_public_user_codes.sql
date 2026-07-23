SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS ensure_public_user_codes;

DELIMITER $$
CREATE PROCEDURE ensure_public_user_codes()
BEGIN
  DECLARE target_user_id BIGINT DEFAULT NULL;
  DECLARE candidate_code CHAR(5) DEFAULT NULL;
  DECLARE allocation_attempts INT DEFAULT 0;
  DECLARE invalid_code_count BIGINT DEFAULT 0;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'public_code'
  ) THEN
    ALTER TABLE users ADD COLUMN public_code CHAR(5) NULL AFTER id;
  END IF;

  SELECT COUNT(*)
  INTO invalid_code_count
  FROM users
  WHERE public_code IS NOT NULL
    AND public_code NOT REGEXP '^[1-9][0-9]{4}$';

  IF invalid_code_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'public user code migration blocked: invalid existing code';
  END IF;

  WHILE EXISTS (SELECT 1 FROM users WHERE public_code IS NULL) DO
    SELECT id
    INTO target_user_id
    FROM users
    WHERE public_code IS NULL
    ORDER BY id
    LIMIT 1;

    SET allocation_attempts = 0;
    SET candidate_code = NULL;
    WHILE candidate_code IS NULL AND allocation_attempts < 1000 DO
      SET @next_code = LPAD(FLOOR(10000 + RAND() * 90000), 5, '0');
      IF NOT EXISTS (
        SELECT 1
        FROM users
        WHERE public_code COLLATE utf8mb4_unicode_ci
          = @next_code COLLATE utf8mb4_unicode_ci
      ) THEN
        SET candidate_code = @next_code;
      END IF;
      SET allocation_attempts = allocation_attempts + 1;
    END WHILE;

    IF candidate_code IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'public user code migration blocked: code space exhausted';
    END IF;

    UPDATE users
    SET public_code = candidate_code
    WHERE id = target_user_id
      AND public_code IS NULL;
  END WHILE;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND index_name = 'uk_users_public_code'
  ) THEN
    CREATE UNIQUE INDEX uk_users_public_code ON users(public_code);
  END IF;

  ALTER TABLE users MODIFY COLUMN public_code CHAR(5) NOT NULL;
END $$
DELIMITER ;

CALL ensure_public_user_codes();
DROP PROCEDURE IF EXISTS ensure_public_user_codes;

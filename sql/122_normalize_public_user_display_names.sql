SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE users
SET nickname = CONCAT('用户', public_code),
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND public_code REGEXP '^[1-9][0-9]{4}$'
  AND (
    REPLACE(REPLACE(TRIM(COALESCE(nickname, '')), ' ', ''), '-', '')
      REGEXP '^([+]86|86)?1[0-9]{10}$'
    OR (
      TRIM(COALESCE(nickname, '')) = ''
      AND REPLACE(REPLACE(TRIM(COALESCE(username, '')), ' ', ''), '-', '')
        REGEXP '^([+]86|86)?1[0-9]{10}$'
    )
  );

UPDATE users
SET nickname = CONCAT('注销用户', public_code),
    avatar_url = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 1
  AND public_code REGEXP '^[1-9][0-9]{4}$'
  AND (
    nickname IS NULL
    OR nickname <> CONCAT('注销用户', public_code)
    OR avatar_url IS NOT NULL
  );

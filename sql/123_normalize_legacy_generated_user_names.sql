SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE users
SET nickname = CONCAT(CONVERT(0xE794A8E688B7 USING utf8mb4), public_code),
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND public_code REGEXP '^[1-9][0-9]{4}$'
  AND HEX(nickname) LIKE 'E794A8E688B7%'
  AND SUBSTRING(nickname, 3) REGEXP '^([0-9]{5}|[0-9]{9})$'
  AND nickname <> CONCAT(CONVERT(0xE794A8E688B7 USING utf8mb4), public_code);

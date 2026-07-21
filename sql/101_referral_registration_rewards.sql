SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS referral_registration_rewards (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  beneficiary_user_id BIGINT NOT NULL,
  beneficiary_role VARCHAR(16) NOT NULL,
  reward_credits INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREDITED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_referral_registration_reward_role (referral_id, beneficiary_role),
  KEY idx_referral_registration_reward_user (beneficiary_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

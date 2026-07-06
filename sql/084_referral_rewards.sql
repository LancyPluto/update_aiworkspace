SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS user_referrals (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  inviter_user_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL,
  invite_code VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_referrals_invitee (invitee_user_id),
  KEY idx_user_referrals_inviter (inviter_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referral_rewards (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  inviter_user_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL,
  recharge_order_id BIGINT NOT NULL,
  reward_credits INT NOT NULL,
  reward_rate DECIMAL(10,4) NOT NULL DEFAULT 0.1000,
  status VARCHAR(32) NOT NULL DEFAULT 'CREDITED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_referral_rewards_order (recharge_order_id),
  KEY idx_referral_rewards_inviter (inviter_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

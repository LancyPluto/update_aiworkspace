-- Split credit balance into membership (subscription/recharge) and gift (redeemed gift cards).
-- Consumption deducts membership_balance first, then gift_balance.

ALTER TABLE credit_accounts
  ADD COLUMN membership_balance INT NOT NULL DEFAULT 0 AFTER balance,
  ADD COLUMN gift_balance INT NOT NULL DEFAULT 0 AFTER membership_balance;

UPDATE credit_accounts
SET membership_balance = balance,
    gift_balance = 0
WHERE membership_balance = 0 AND gift_balance = 0;

SET NAMES utf8mb4;

-- Let admins tune pre-execution estimates for IMAGE_TOKEN models such as GPT-image2.
-- NULL means inherit from the next broader pricing scope, finally falling back to 8000/8000.

ALTER TABLE pricing_margins
  ADD COLUMN image_estimate_input_tokens INT NULL AFTER min_credits,
  ADD COLUMN image_estimate_output_tokens INT NULL AFTER image_estimate_input_tokens;

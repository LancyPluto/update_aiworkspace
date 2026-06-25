-- 075: Fix broken /cdn/ proxy URLs after nginx proxy removal
-- The /cdn/ location block was removed from nginx (commit dd7cfb32).
-- Community posts and task results still reference https://wlcloudai.com/cdn/...
-- These need to point to the CDN domain https://cdn.wlcloudai.com/ instead.

-- Fix community post cover URLs
UPDATE community_posts
SET cover_url = REPLACE(cover_url, 'https://wlcloudai.com/cdn/', 'https://cdn.wlcloudai.com/')
WHERE cover_url LIKE 'https://wlcloudai.com/cdn/%';

-- Fix task result resource URLs embedded in content_text JSON
UPDATE ai_result_resources
SET content_text = REPLACE(content_text, 'https://wlcloudai.com/cdn/', 'https://cdn.wlcloudai.com/')
WHERE content_text LIKE '%https://wlcloudai.com/cdn/%';

-- Repair tool_name (and related labels) corrupted to "AI ????????" when seed SQL
-- was imported with a non-utf8mb4 client (e.g. Windows PowerShell pipe).
-- Safe to re-run: only updates rows that still contain '?' in tool_name.

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

UPDATE tool_categories SET category_name = '文案生成' WHERE category_code = 'copywriting' AND category_name LIKE '%?%';
UPDATE tool_categories SET category_name = '智能体' WHERE category_code = 'agent' AND category_name LIKE '%?%';

UPDATE ai_tools SET tool_name = 'AI 小红书文案生成器' WHERE tool_code = 'xiaohongshu_copywriting' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 公众号/长文生成器' WHERE tool_code = 'wechat_longform_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 商品标题优化器' WHERE tool_code = 'product_title_optimizer' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 电商活动方案生成器' WHERE tool_code = 'ecommerce_campaign_planner' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 商品详情页文案生成器' WHERE tool_code = 'product_detail_page_copywriter' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 短视频脚本生成器' WHERE tool_code = 'short_video_script_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 短视频选题生成器' WHERE tool_code = 'short_video_topic_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 直播话术生成器' WHERE tool_code = 'live_stream_script_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 客户跟进话术生成器' WHERE tool_code = 'customer_followup_script_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 异议处理话术生成器' WHERE tool_code = 'objection_handling_script_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 门店活动策划器' WHERE tool_code = 'store_campaign_planner' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 朋友圈文案生成器' WHERE tool_code = 'moments_copywriting_generator' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 数字人视频生成智能体' WHERE tool_code = 'digital_human_agent' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI 漫剧生成智能体' WHERE tool_code = 'ai_comic_drama_agent' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = '企业诊断智能体' WHERE tool_code = 'enterprise_diagnosis_agent' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = '社交网络评论洞察智能体' WHERE tool_code = 'social_media_comment_insights_agent' AND tool_name LIKE '%?%';
UPDATE ai_tools SET tool_name = 'AI PPT 生成器' WHERE tool_code = 'banana_ppt_generator' AND tool_name LIKE '%?%';

SET NAMES utf8mb4;

-- happyhorse_video_edit 的 ai-tool-ui 里 comparisonEffectUrl 指向静态 jpg，首页会优先展示裂图而非 cover_url 的 mp4
UPDATE ai_tools
SET config_note = CONCAT(
  TRIM(SUBSTRING_INDEX(config_note, '<!-- ai-tool-ui:', 1)),
  '\n\n<!-- ai-tool-ui:{"mediaDisplayMode":"effect","modelIconUrl":"","comparisonOriginalUrl":"","comparisonEffectUrl":"","demoThumbnails":[],"audioPreviewUrl":"","heroTitle":"","heroSubtitle":"","useCases":[],"steps":[],"recommendedToolCodes":[],"beforeVideoUrl":"","afterVideoUrl":""} -->'
)
WHERE tool_code = 'happyhorse_video_edit'
  AND config_note LIKE '%ai-tool-ui:%';

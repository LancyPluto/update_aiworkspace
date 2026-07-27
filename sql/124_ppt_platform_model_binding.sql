-- PPT uses the shared platform model catalog. Existing installations gain the
-- zero-config strategy without copying model IDs or provider credentials.

UPDATE ai_tools
SET config_note = REPLACE(
      config_note,
      '"creationTypes":',
      '"modelBindingStrategy":"PLATFORM_AUTO","preferredImageFamily":"gpt-image-2","creationTypes":'
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'banana_ppt_generator'
  AND config_note LIKE '%"integrationMode":"PPT_WORKSPACE"%'
  AND config_note NOT LIKE '%"modelBindingStrategy"%';

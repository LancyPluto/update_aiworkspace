-- Memory 系统改进：为 agent_workspace_memory_items 添加 FULLTEXT 索引
-- ngram 解析器默认分词长度为 2，适合中文场景

ALTER TABLE agent_workspace_memory_items
  ADD FULLTEXT INDEX ft_memory_search (title, content)
  WITH PARSER ngram;

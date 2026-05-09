# 输出协议 V1

## 返回目标

- 生成结果默认使用 `MARKDOWN` 返回。
- Worker 在保存 `contentText` 的同时，尝试将结果解析为结构化 `contentJson`。
- 结构化字段用于前端后续分块展示和复制。

## Markdown 结构

```markdown
## 标题建议
1. ...
2. ...
3. ...

## 正文
...

## 标签建议
#...
#...
#...

## 行动引导
...
```

## contentJson 结构

```json
{
  "titles": ["标题 1", "标题 2", "标题 3"],
  "content": "正文内容",
  "hashtags": ["#标签1", "#标签2"],
  "cta": "行动引导"
}
```

## 校验规则

- `titles` 至少 1 条，推荐 3 条。
- `content` 不能为空。
- `hashtags` 至少 1 条，推荐 5 到 8 条。
- `cta` 不能为空。
- 缺少核心区块时，Worker 应判定结果不符合工具输出要求。

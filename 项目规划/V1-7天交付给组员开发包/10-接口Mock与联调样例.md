# 10. 接口 Mock 与联调样例

本文用于前端和后台在后端接口未完成前先开发页面。真实接口完成后，字段必须与本文保持一致。

## 1. 用户登录

```http
POST /api/v1/auth/login
```

请求：

```json
{
  "account": "user1",
  "password": "123456"
}
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "accessToken": "mock-user-token",
    "user": {
      "id": 1001,
      "nickname": "测试用户",
      "userType": "USER"
    }
  }
}
```

## 2. 管理员登录

```http
POST /api/admin/v1/auth/login
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "accessToken": "mock-admin-token",
    "admin": {
      "id": 1,
      "nickname": "管理员",
      "userType": "ADMIN"
    }
  }
}
```

## 3. 工具列表

```http
GET /api/v1/tools
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "list": [
      {
        "toolCode": "xiaohongshu_copywriting",
        "toolName": "小红书文案生成",
        "description": "根据产品信息生成小红书风格文案",
        "coverUrl": "",
        "categoryName": "文案生成",
        "estimatedCreditCost": 10,
        "status": "ONLINE"
      }
    ],
    "total": 1
  }
}
```

## 4. 工具详情

```http
GET /api/v1/tools/xiaohongshu_copywriting
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "toolCode": "xiaohongshu_copywriting",
    "toolName": "小红书文案生成",
    "description": "根据产品信息生成小红书风格文案",
    "estimatedCreditCost": 10,
    "fields": [
      {
        "fieldKey": "productName",
        "fieldName": "产品名称",
        "fieldType": "text",
        "placeholder": "请输入产品名称",
        "required": true,
        "sortOrder": 1
      },
      {
        "fieldKey": "targetCustomer",
        "fieldName": "目标用户",
        "fieldType": "textarea",
        "placeholder": "请输入目标用户",
        "required": true,
        "sortOrder": 2
      },
      {
        "fieldKey": "style",
        "fieldName": "文案风格",
        "fieldType": "select",
        "required": true,
        "options": [
          { "label": "种草", "value": "种草" },
          { "label": "专业", "value": "专业" }
        ],
        "sortOrder": 3
      }
    ]
  }
}
```

## 5. 创建任务

```http
POST /api/v1/tasks
```

请求：

```json
{
  "toolCode": "xiaohongshu_copywriting",
  "params": {
    "productName": "五一护理套餐",
    "targetCustomer": "年轻女性",
    "style": "种草"
  },
  "clientRequestId": "uuid-from-frontend"
}
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "status": "QUEUED",
    "progress": 0,
    "progressMessage": "任务已排队"
  }
}
```

## 6. 查询任务状态

```http
GET /api/v1/tasks/90001/status
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "status": "PROCESSING",
    "progress": 45,
    "progressMessage": "AI 正在生成内容"
  }
}
```

## 7. 查询任务详情

```http
GET /api/v1/tasks/90001
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605070001",
    "toolCode": "xiaohongshu_copywriting",
    "toolName": "小红书文案生成",
    "status": "SUCCESS",
    "params": {
      "productName": "五一护理套餐",
      "targetCustomer": "年轻女性",
      "style": "种草"
    },
    "result": {
      "resourceType": "MARKDOWN",
      "contentText": "这里是生成结果"
    },
    "createdAt": "2026-05-07 10:00:00",
    "finishedAt": "2026-05-07 10:00:20"
  }
}
```

## 8. 常见失败响应

算力不足：

```json
{
  "code": "CREDIT_NOT_ENOUGH",
  "message": "算力不足",
  "data": null
}
```

工具下架：

```json
{
  "code": "TOOL_OFFLINE",
  "message": "工具未上线",
  "data": null
}
```

无权限：

```json
{
  "code": "FORBIDDEN",
  "message": "无权限访问",
  "data": null
}
```


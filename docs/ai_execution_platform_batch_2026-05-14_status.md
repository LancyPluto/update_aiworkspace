# AI 执行平台批次实现状态

日期：2026-05-14

## 本批次已完成

- 管理端 `/commerce` 节点列表已补快捷上下线操作，可将节点切换为 `AVAILABLE` / `DISABLED`，下线时会释放当前并发占用。
- 用户端模型工作台已优化订阅、额度、节点不可用等错误提示；SSE 流式接口在非 2xx 时会解析后端业务错误，不再只显示泛化网络失败。
- RabbitMQ 已补三层 TTL 延迟重试队列：默认 `5s / 30s / 120s`，worker 发布到 retry/dead 队列启用 publisher confirm 和 `mandatory`，目标队列缺失时不会 ack 丢消息。
- Docker Compose 已让 worker 等待 RabbitMQ 健康，并保留启动失败指数退避重连。
- 支付回调已加入公开路径放行、金额/渠道校验、订单状态 CAS 更新，并新增 `payment_fulfillments(order_id UNIQUE)` 作为发放级幂等锁，避免重复回调或异常补偿造成重复发放。

## 验证结果

- `backend`: `mvn -q -DskipTests compile`
- `user-web`: `npm run build`
- `admin-frontend`: `npm run build`
- `worker`: `python -m py_compile worker\task_queue\rabbitmq_consumer.py worker\config.py`
- `deploy`: `docker compose config --quiet`

以上验证均已通过。

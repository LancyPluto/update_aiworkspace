# Workflow P0 RabbitMQ 真实发布门禁

这个门禁使用一次性的本地 RabbitMQ，真实验证发布确认、不可路由退回和 DLQ 手动重放。默认不会运行；只有显式设置 `WORKFLOW_RABBIT_GATE_ENABLED=true` 才会启用。

测试会拒绝连接非 `127.0.0.1`/`localhost` 的 broker。它创建的队列和交换机都以 `workflow_p0_gate_` 开头，并在每个用例结束后删除。

## 1. 启动隔离 broker

下面的 PowerShell 命令使用独立容器、独立临时数据和 `5673` 端口，不复用项目生产或开发 RabbitMQ：

```powershell
docker run --rm -d --name workflow-p0-gate-rabbitmq `
  -e RABBITMQ_DEFAULT_USER=workflow_gate `
  -e RABBITMQ_DEFAULT_PASS=workflow_gate_local `
  -p 127.0.0.1:5673:5672 `
  rabbitmq:3.13-management
```

## 2. 运行单个门禁测试

```powershell
$env:WORKFLOW_RABBIT_GATE_ENABLED='true'
$env:WORKFLOW_RABBIT_GATE_HOST='127.0.0.1'
$env:WORKFLOW_RABBIT_GATE_PORT='5673'
$env:WORKFLOW_RABBIT_GATE_USERNAME='workflow_gate'
$env:WORKFLOW_RABBIT_GATE_PASSWORD='workflow_gate_local'
$env:WORKFLOW_RABBIT_GATE_VHOST='/'

mvn -o '-Dmaven.repo.local=.m2' -f backend/pom.xml '-Dtest=TaskQueueRabbitMqRealGateTest' test
```

预期通过 3 个场景：

1. 正常路由必须收到 publisher ACK，且消息真实进入测试队列。
2. `mandatory=true` 发布到不存在的 routing key 时，必须以 `returned unroutable / NO_ROUTE` 失败。
3. 消息进入 DLQ 后，手动重放必须先确认新消息发布成功，再 ACK 并删除原死信。

## 3. 清理

```powershell
docker stop workflow-p0-gate-rabbitmq
Remove-Item Env:WORKFLOW_RABBIT_GATE_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:WORKFLOW_RABBIT_GATE_HOST -ErrorAction SilentlyContinue
Remove-Item Env:WORKFLOW_RABBIT_GATE_PORT -ErrorAction SilentlyContinue
Remove-Item Env:WORKFLOW_RABBIT_GATE_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:WORKFLOW_RABBIT_GATE_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:WORKFLOW_RABBIT_GATE_VHOST -ErrorAction SilentlyContinue
```

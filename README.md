# Friend Feed MVP

一个以 MySQL 为事实来源、Redis 为可降级缓存的好友动态 Feed。采用写扩散：发布事务写入帖子、作者 Inbox 和 Outbox，Dispatcher 将事件可靠投递到 Kafka，Consumer Group 异步且幂等地写入好友 Inbox。

## 关键正确性约束

- **发布不丢事件**：`posts` 与 `outbox_events` 在同一个 MySQL 事务内提交。
- **发布接口幂等**：`(author_id, idempotency_key)` 是唯一键；相同键和相同请求返回首次结果，相同键用于不同请求返回 `409 Conflict`。
- **重复消费安全**：`feed_inbox(owner_id, post_id)` 是唯一键，使用 `INSERT IGNORE` 幂等写入。
- **异步写扩散**：MySQL Outbox 是状态事实来源，Kafka 负责传输；Kafka 至少一次投递不会造成重复 Inbox。
- **失败可恢复**：事件经历 `PENDING → PROCESSING → DISPATCHED → PROCESSED`，失败按指数退避重试，超过阈值进入 `FAILED` 死信状态，超时任务会自动回收。
- **权限实时生效**：Inbox 只是候选集；每次读取都重新检查帖子状态、当前好友关系、双向拉黑、包含名单和排除名单。
- **稳定分页**：按 `(published_at DESC, post_id DESC)` 排序，cursor 同时携带微秒时间和帖子 ID，不使用 `OFFSET`。
- **Redis 故障可降级**：Redis 只缓存帖子快照；读取/写入 Redis 失败时继续访问 MySQL。
- **身份不可伪造**：业务接口仅接受 Spring Security 验证过的 Bearer JWT，客户端提交的 `X-User-Id` 不再参与身份判断。

## 启动

要求 JDK 21+、Maven 和 Docker。Compose 会启动 MySQL、Redis 与单节点 Kafka。

```bash
docker compose up -d
export JWT_SECRET=replace-with-at-least-32-random-bytes
mvn spring-boot:run
```

PowerShell 使用 `$env:JWT_SECRET='replace-with-at-least-32-random-bytes'`。仓库中的默认密钥只用于本地开发；部署时必须通过环境变量设置至少 32 字节的随机值。可用 `JWT_TTL` 调整有效期，默认为 `2h`。

如果本机端口已被占用，可设置 `MYSQL_PORT`、`REDIS_PORT`、`KAFKA_PORT` 改变 Compose 暴露端口，并通过 `MYSQL_URL`、`REDIS_PORT`、`KAFKA_BOOTSTRAP_SERVERS` 告诉应用对应地址。

服务默认监听 `http://localhost:8080`，健康检查为 `GET /actuator/health`。

## API 示例

注册两个用户。注册成功会直接返回 Access Token：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","nickname":"Alice","password":"alice-pass-123"}'
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","nickname":"Bob","password":"bob-pass-123"}'
```

登录：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice-pass-123"}'
```

响应中的 `accessToken` 用于后续请求：

```text
Authorization: Bearer ACCESS_TOKEN
```

建立双向好友关系并发布动态：

```bash
curl -X PUT http://localhost:8080/api/relationships/friends/2 \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN"
curl -X POST http://localhost:8080/api/posts \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: 58d474a8-00a7-4c56-9959-6f1b0a775462" \
  -d '{"content":"hello feed","visibility":"ALL_FRIENDS"}'
```

`Idempotency-Key` 必须是客户端生成的 UUID，并应在一次逻辑发布的所有网络重试中保持不变。更换内容、可见范围或目标用户时必须生成新键。

读取 Bob 的 Feed；下一页把响应中的 `nextCursor` 原样传回：

```bash
curl "http://localhost:8080/api/feed?size=20" \
  -H "Authorization: Bearer BOB_ACCESS_TOKEN"
curl "http://localhost:8080/api/feed?size=20&cursor=NEXT_CURSOR" \
  -H "Authorization: Bearer BOB_ACCESS_TOKEN"
```

发布权限类型：

- `ALL_FRIENDS`：当前有效好友可见。
- `ONLY_ME`：仅作者可见。
- `INCLUDE_LIST`：仅 `targetUserIds` 中仍为好友且未互相拉黑的人可见。
- `EXCLUDE_LIST`：除 `targetUserIds` 外的有效好友可见。

删除好友或任一方拉黑后，旧 Inbox 行无需立即清除，读取侧会立刻过滤。恢复好友关系不会补发未曾扩散的历史动态；之前已经写入 Inbox 且仍有效的动态会按当前权限重新可见。

## 测试

```bash
mvn test
```

单元测试覆盖 cursor 编解码、删除/好友/拉黑后的权限原则，以及“扫描行”和“实际返回行”不同情况下的翻页边界。

需要本机 `3307` 端口有测试 MySQL 时，可显式运行内嵌真实 Kafka Broker 的端到端用例：

```bash
mvn -DrunKafkaIntegration=true -Dtest=KafkaFanoutIntegrationTest test
mvn -DrunMySqlIntegration=true -Dtest=OutboxRepositoryIntegrationTest test
```

## Outbox 与 Kafka 运维

主要配置：

- `feed.fanout.max-attempts`：最大尝试次数，默认 8。
- `feed.fanout.initial-backoff` / `max-backoff`：指数退避下限与上限，默认 1 秒和 15 分钟。
- `feed.fanout.processing-timeout`：`PROCESSING` / `DISPATCHED` 超时回收阈值，默认 2 分钟。
- `feed.fanout.topic`：Kafka Topic，默认 `feed.post-published.v1`。

Prometheus 指标在 `/actuator/prometheus` 输出：

- `feed_outbox_backlog`：未完成事件数。
- `feed_outbox_failed`：FAILED 死信数。
- `feed_outbox_oldest_age_seconds`：最老积压事件年龄。
- `feed_outbox_processing_latency_seconds`：最近 5 分钟已完成事件的平均端到端延迟。

管理员可查看快照并重放死信事件：

```bash
curl http://localhost:8080/api/admin/outbox/metrics \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"

curl -X POST http://localhost:8080/api/admin/outbox/123/replay \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

只有数据库 `users.role='ADMIN'` 的用户重新登录后签发的 JWT 才包含管理员角色。重放仅接受 `FAILED` 事件，会清零尝试次数并立即重新进入投递流程。

## 当前范围

本版采用短期 HS256 JWT 和单 Kafka 集群，尚未包含刷新令牌、主动登出/撤销、密码找回、邮箱/手机验证、图片/视频、点赞评论、关系申请流程、历史动态回填和大 V 读扩散。生产环境若有多个独立服务，建议迁移到独立身份服务和非对称密钥签名，并把 Kafka Topic 副本数提升到至少 3。

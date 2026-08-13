# 预约链路 A/B 与热点预约 PTS 压测包

## A/B 对比口径

- **A / SQL 基线**：NORMAL 时段，纯 MySQL 条件扣减和同步事务落库。
- **B / 优化版**：HOT 时段，RocketMQ 事务半消息、Redis Lua 预占、MQ 异步确认、MySQL 最终落库和 Outbox 结果事件。

两组都使用当前代码、同一 `/reservation`、同一 JMX、500 个预登录独立用户和 200 个名额。限流、提醒、自动取消与审计在对比中关闭，避免污染核心指标。

```bash
# A：准备纯 SQL 基线
COUNT=500 QUOTA=200 /opt/lab-booking/loadtest/prepare-sql-baseline-pts.sh

# B：准备 Redis + MQ 优化版
COUNT=500 QUOTA=200 /opt/lab-booking/loadtest/prepare-optimized-pts.sh
```

分别上传脚本输出的 CSV；PTS 参数都设为 500 并发、5 秒升压、每用户一次。压测后分别执行：

```bash
/opt/lab-booking/loadtest/verify-sql-baseline-pts.sh <SQL_SLOT_ID> 200
/opt/lab-booking/loadtest/verify-hot-reservation-pts.sh <HOT_SLOT_ID> 200
```

这个目录只用于 ECS 上的隔离库 `lab_booking_loadtest`。场景刻意不包含登录：登录和 JWT 签发会污染热点预约受理接口的吞吐与延迟。压测请求仍经过真实 JWT 鉴权，并执行 `RocketMQ 半消息 → Redis Lua 预占 → 提交消息 → MySQL 异步确认 → Outbox 结果事件`。

## 目标场景

- 500 个不同的预登录用户，在 5 秒内各提交一次 `POST /reservation`。
- 同一 HOT 时段库存为 200；预期恰好 200 个 HTTP `202/PENDING`，其余为 HTTP `409`（正常售罄），不能出现 5xx。
- 请求线程不查询 MySQL、不等待最终落库；RocketMQ Consumer 以 4 并发平稳完成确认。
- 最终必须满足：200 条 `CONFIRMED`、200 条预约、Redis/MySQL 库存均为 0、零超卖、零重复预约。

## ECS 准备

将 `prepare-hot-reservation-pts.sh` 与 `verify-hot-reservation-pts.sh` 上传到 ECS 的 `/opt/lab-booking/loadtest/` 后执行：

```bash
chmod 700 /opt/lab-booking/loadtest/prepare-hot-reservation-pts.sh \
  /opt/lab-booking/loadtest/verify-hot-reservation-pts.sh \
  /opt/lab-booking/loadtest/monitor-hot-reservation.sh

COUNT=500 QUOTA=200 /opt/lab-booking/loadtest/prepare-hot-reservation-pts.sh
```

脚本会创建新的带运行编号的资源、时段、500 个用户，以及包含 Token 和独立 UUID `requestId` 的 CSV；它只接受 `lab_booking_loadtest`，不会删除旧数据，也不会输出 JWT。环境文件必须显式设置 `LOADTEST_MQ_ENABLED=true`。完成后会重启隔离后端并预热 Redis v2 快照，首次请求不会承担缓存构建成本。

可选地在发起 PTS 前开启监控：

```bash
nohup /opt/lab-booking/loadtest/monitor-hot-reservation.sh \
  /opt/lab-booking/loadtest/ecs-metrics-<RUN_ID>.csv >/dev/null 2>&1 &
echo $!
```

## PTS 设置

1. 新建 **JMeter 压测** 场景，上传 `hot-reservation-pts.jmx` 和准备脚本生成的 `hot-reservation-tokens.csv`。
2. 使用阿里云 VPC 内网压测，目标保持 JMX 默认的 `172.18.225.136:8082`。
3. 首次正式跑：500 线程、5 秒调速、循环 1 次。不要把登录接口加入场景。
4. 关注 PTS 的 TPS、p50/p95/p99、HTTP 202/409/5xx 分布，并在 RocketMQ 控制台记录积压峰值和清空耗时。409 是库存售罄的预期业务结果；5xx、401、429 才是异常。
5. 压测结束后停止监控，并执行：

```bash
/opt/lab-booking/loadtest/verify-hot-reservation-pts.sh <SLOT_ID> 200
```

通过标准：`booked=200`、`confirmedRequests=200`、`mysqlRemain=0`、`redisRemain=0`、`duplicateBookedUsers=0`。这组最终一致性结果和 PTS 的受理接口 TPS/P95、MQ 积压峰值及清空耗时一起，才构成可写入简历的证据。

## 两个补充场景

**证明 MQ 削峰：** 在环境文件中临时设置 `APP_RESERVATION_COMMAND_CONSUMER_ENABLED=false` 后重启服务，再执行同一份 500/200 场景。接口仍应快速返回 200 个 `202/PENDING`，此时 MySQL 暂无对应预约。随后改回 `true` 并重启，等待积压清空，再运行校验脚本；最终仍应确认 200 条且库存一致。

**证明幂等：** 创建一个独立时段：

```bash
COUNT=1 QUOTA=1 /opt/lab-booking/loadtest/prepare-hot-reservation-pts.sh
```

使用同一 JMX 和这一行 CSV，设置 1 线程、循环 100 次。100 次请求复用同一个 `Idempotency-Key`，最终校验必须只有 1 条预约、只扣 1 份库存。

> Token 默认 24 小时过期；准备完成后当天发起 PTS。每次正式压测都创建新时段，不复用已被抢空的库存。

如果只做过场景调试、尚未进行正式压测，可重置当前 `PTS-HOT-*` 时段：

```bash
/opt/lab-booking/loadtest/reset-hot-reservation-pts.sh <SLOT_ID>
```

脚本只接受带 `PTS-HOT-` 资源编号的隔离 HOT 时段；会删除该时段的预约及关联提醒/Outbox 行，恢复数据库余量并重新预热 Redis。

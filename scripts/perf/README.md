# 热点预约 PTS 压测包

这个目录只用于 ECS 上的隔离库 `lab_booking_loadtest`。场景刻意不包含登录：登录和 JWT 签发会污染热点预约的吞吐与延迟，压测请求仍经过真实 JWT 鉴权、资源/时段校验、Redis Lua 原子预占、MySQL 预约事务。

## 目标场景

- 500 个不同的预登录用户，在 5 秒内各提交一次 `POST /reservation`。
- 同一 HOT 时段库存为 20；预期 20 个 HTTP `200`，其余为 HTTP `409`（正常售罄），而不是 5xx。
- 当前正式场景为 Redis 热点快照校验、Lua 原子预占 + 同步 MySQL 落库；售罄和重复提交在 Redis 返回，不进入 MySQL。`LOADTEST_MQ_ENABLED=false`，因此不把 MQ 后置通知链路混入核心 TPS。准备脚本会拒绝 MQ 已开启的环境。

## ECS 准备

将 `prepare-hot-reservation-pts.sh` 与 `verify-hot-reservation-pts.sh` 上传到 ECS 的 `/opt/lab-booking/loadtest/` 后执行：

```bash
chmod 700 /opt/lab-booking/loadtest/prepare-hot-reservation-pts.sh \
  /opt/lab-booking/loadtest/verify-hot-reservation-pts.sh \
  /opt/lab-booking/loadtest/monitor-hot-reservation.sh

COUNT=500 QUOTA=20 /opt/lab-booking/loadtest/prepare-hot-reservation-pts.sh
```

脚本会创建新的带运行编号的资源、时段、500 个用户和仅含 Token 的 CSV；它只接受 `lab_booking_loadtest`，不会删除旧数据，也不会输出 JWT。完成后会重启隔离后端并显式预热新 HOT 时段的 Redis 时段快照、库存和防重键，首次请求不会承担缓存构建成本。

可选地在发起 PTS 前开启监控：

```bash
nohup /opt/lab-booking/loadtest/monitor-hot-reservation.sh \
  /opt/lab-booking/loadtest/ecs-metrics-<RUN_ID>.csv >/dev/null 2>&1 &
echo $!
```

## PTS 设置

1. 新建 **JMeter 压测** 场景，上传 `hot-reservation-pts.jmx` 和准备脚本生成的 `hot-reservation-<RUN_ID>-tokens.csv`。
2. 使用阿里云 VPC 内网压测，目标保持 JMX 默认的 `172.18.225.136:8082`。
3. 首次正式跑：500 线程、5 秒调速、循环 1 次。不要把登录接口加入场景。
4. 关注 PTS 的 TPS、p50/p95/p99、HTTP 200/409/5xx 分布和 ECS 监控。409 是库存售罄的预期业务结果；5xx、401、429 才是异常。
5. 压测结束后停止监控，并执行：

```bash
/opt/lab-booking/loadtest/verify-hot-reservation-pts.sh <SLOT_ID> 20
```

通过标准：`booked=20`、`remain=0`、`duplicateBookedUsers=0`、`pendingRequests=0`。这组一致性结果和 PTS 的时延/TPS 一起才构成可写入简历的证据。

> Token 默认 24 小时过期；准备完成后当天发起 PTS。每次正式压测都创建新时段，不复用已被抢空的库存。

如果只做过场景调试、尚未进行正式压测，可重置当前 `PTS-HOT-*` 时段：

```bash
/opt/lab-booking/loadtest/reset-hot-reservation-pts.sh <SLOT_ID>
```

脚本只接受带 `PTS-HOT-` 资源编号的隔离 HOT 时段；会删除该时段的预约及关联提醒/Outbox 行，恢复数据库余量并重新预热 Redis。

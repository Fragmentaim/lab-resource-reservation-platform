# 热门预约压测包

本目录用于验证热门时段预约的并发正确性与接口延迟。它只面向隔离环境，**禁止**把初始化脚本指向日常开发库 `lab_booking` 或生产库。

## 设计目标

- 以 `HOT` 时段为目标，验证 Redis Lua 预扣、提交防重、Outbox 和异步落库链路。
- 压测请求使用真实 HTTP、真实注册用户与真实 JWT，不经过前端页面。
- 指标之外必须校验：不超卖、不重复、请求最终收敛、余量与成功预约数一致。

## 目录

```text
loadtest/
  jmeter/hot-reservation-burst.jmx  200 个不同用户同时抢一个时段
  runtime/                          运行时 CSV 与临时文件（Git 忽略）
  results/                          JTL、HTML 报告（Git 忽略）
```

配套脚本在 `scripts/loadtest/`：

1. `Initialize-LoadTestDatabase.ps1`：重建隔离库 `lab_booking_loadtest`。
2. `New-HotReservationSlot.ps1`：创建一条新的 HOT 时段，并输出 `resourceId`、`slotId`。
3. `Provision-LoadTestUsers.ps1`：通过注册接口创建压测用户并输出 CSV。
4. `Invoke-HotReservationBurst.ps1`：以非 GUI 模式运行 JMeter 并生成 HTML 报告。
5. `Validate-HotReservationRun.ps1`：轮询异步请求并校验最终数据一致性。

## 本机准备顺序

```powershell
cd D:\_Projects\01_Java\lab-booking-course

# 仅可重建 lab_booking_loadtest，脚本会拒绝其他库名。
.\scripts\loadtest\Initialize-LoadTestDatabase.ps1

# 使用压测 profile 启动第二个后端实例（默认 8082）。
$env:SPRING_PROFILES_ACTIVE = 'loadtest'
& 'D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd' -f .\backend\pom.xml spring-boot:run
```

另开 PowerShell：

```powershell
cd D:\_Projects\01_Java\lab-booking-course

$slot = .\scripts\loadtest\New-HotReservationSlot.ps1 -Quota 50
.\scripts\loadtest\Provision-LoadTestUsers.ps1 -Count 200

.\scripts\loadtest\Invoke-HotReservationBurst.ps1 `
  -ResourceId $slot.ResourceId -SlotId $slot.SlotId `
  -UsersCsv .\loadtest\runtime\users.csv -Threads 200

.\scripts\loadtest\Validate-HotReservationRun.ps1 `
  -SlotId $slot.SlotId -ExpectedQuota 50
```

默认是 Redis 同步热门预约模式。RocketMQ 服务准备好后，启动后端前设置 `$env:LOADTEST_MQ_ENABLED = 'true'`，即可验证 Outbox + MQ 异步确认链路。压测结束后再运行校验脚本，等待 `PENDING` 请求收敛。

## 服务器迁移

服务器到位后无需改 JMX：设置 `LOADTEST_DB_URL`、`LOADTEST_REDIS_HOST`、`LOADTEST_MQ_NAMESERVER` 后，用 `loadtest` profile 启动服务；压测机执行脚本时将 `-TargetBaseUrl` 改成服务器地址即可。数据库、Redis 和 RocketMQ 不应对公网开放。

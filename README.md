# 实验室资源预约平台

一个面向实验室资源管理场景的前后端分离预约系统。系统覆盖资源浏览、时段预约、热门资源异步确认、预约提醒、未签到自动取消、通知中心和管理后台等功能，并通过 MySQL、Redis 与 RocketMQ 处理预约链路中的并发控制、异步确认、延迟触发和状态一致性问题。

## 功能特性

- 基于 Spring Boot 4 + MyBatis-Plus 实现资源、时段、预约、通知、用户和管理端核心业务。
- 基于数据库事务、库存扣减和唯一约束，保证同一用户同一时段不可重复预约，并避免时段名额超卖。
- 基于 RocketMQ 将热门预约改造为“请求受理 + 异步确认”流程，请求侧返回 `PENDING`，消费侧在事务内完成预约确认与请求状态更新。
- 基于 Redis 实现热点时段库存预占、重复提交控制、接口限流和资源时段缓存，降低高频请求对 MySQL 的直接压力。
- 基于 RocketMQ 4.9.6 延迟等级 + 自推进重投实现预约前提醒、未签到自动取消和热门请求超时失败。
- 基于 outbox 表解决“业务事务成功但 MQ 投递失败”的可靠投递问题，消费端通过状态机和条件更新保证幂等。

## 技术栈

- 后端：Java 17、Spring Boot 4、MyBatis-Plus、MySQL、JWT
- 中间件：Redis / Memurai、RocketMQ 4.9.6
- 前端：Vue 3、Vite、Naive UI、Pinia、Vue Router、Axios
- 工程与测试：Maven、JUnit 5、Mockito、Git

## 架构概览

```text
Vue 前端
  |
  | REST API / JWT
  v
Spring Boot 后端
  |
  |-- MySQL：资源、时段、预约、请求单、通知、outbox
  |-- Redis：热点库存、重复提交控制、限流、资源时段缓存
  |-- RocketMQ：热门预约异步确认、延迟提醒、自动取消、请求超时
```

## 核心链路

### 普通预约

1. 用户选择资源时段并提交预约。
2. 后端校验资源、时段、状态、剩余名额和用户重复预约。
3. 事务内扣减 `resource_slot.remain_quota`，插入 `reservation`。
4. 创建预约前提醒和未签到自动取消延迟事件。
5. 用户在“我的预约”中查看预约状态。

### 热门预约异步确认

1. 请求侧完成参数校验、限流、重复提交控制和 Redis 预占。
2. 写入 `reservation_request`，状态为 `PENDING`。
3. relay 将请求投递到 RocketMQ。
4. 消费侧将请求从 `PENDING` 原子更新为 `PROCESSING`，避免重复消费并发创建预约。
5. 消费侧事务内扣减数据库名额、创建预约、更新请求为 `SUCCESS`。
6. 如果请求超时仍未成功，由延迟消息标记为 `FAILED` 并释放 Redis 预占。

### MQ4 延迟消息

统一延迟消息结构：

```json
{
  "eventId": "RESERVATION_AUTO_CANCEL:1001",
  "eventType": "RESERVATION_AUTO_CANCEL",
  "businessKey": "1001",
  "deliverAt": "2026-06-08T10:15:00",
  "payload": "{\"reservationId\":1001}"
}
```

topic 使用 `reservation-delay`，tag 包括：

- `reservation-reminder`：预约前提醒
- `reservation-auto-cancel`：预约开始后未签到自动取消
- `reservation-timeout`：热门预约请求超时失败

RocketMQ 4.x 只有固定延迟等级，本项目采用“向下取可用等级 + 消费端自推进重投”：超过最大延迟等级的消息先投递到最大等级，到达消费者后如果 `deliverAt` 未到，则按剩余时间再次投递，直到真正到期再执行业务逻辑。

### 未签到自动取消

1. 预约成功后设置 `auto_cancel_deadline = slot_start_datetime + 15 分钟`。
2. 写入 `RESERVATION_AUTO_CANCEL` 延迟事件。
3. 用户可在预约开始前 30 分钟至自动取消截止时间内调用 `PUT /reservation/{id}/check-in` 签到。
4. 自动取消消息到期后，仅当预约仍是 `BOOKED` 且 `checked_in_at IS NULL` 时才改为 `CANCELLED`。
5. 自动取消成功后恢复名额、释放 HOT Redis 预占语义，并写入用户通知。

## 可靠性设计

- **请求幂等**：`reservation_request.active_key = userId:slotId` 保证同一用户同一时段只保留一个未完成热门预约请求。
- **预约幂等**：`reservation` 使用 `user_id + slot_id + is_active` 唯一约束，终态预约清空 `is_active`，允许后续重新提交。
- **消费幂等**：MQ 消费端按业务状态做条件更新，已签到、已取消、已完成的预约直接消费成功。
- **可靠投递**：业务事务内写 outbox，relay 负责投递 MQ，失败后记录重试次数和错误原因。
- **失败重试**：业务异常或坏消息返回 RocketMQ 重试；超过最大重试次数后依赖 RocketMQ 死信队列进行隔离和后续排查。

## 目录结构

```text
lab-resource-reservation-platform/
  backend/              Spring Boot 后端
  frontend/             Vue 3 + Vite 前端
  sql/                  数据库初始化和升级脚本
  scripts/              本地启动脚本
  README.md
```

## 本地启动

### 初始化数据库

先启动 MySQL，然后执行：

```sql
source sql/lab-booking-rebuild-init.sql;
```

默认数据库配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lab_booking
    username: root
    password: ""
```

旧库升级可按需执行：

```text
sql/reservation-request-upgrade.sql
sql/reservation-request-active-key-upgrade.sql
sql/delay-message-outbox-upgrade.sql
sql/reservation-auto-cancel-upgrade.sql
```

### 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认地址：

```text
http://127.0.0.1:8081
```

### 启动 MQ 增强模式

普通启动只依赖 MySQL。若要验证 Redis / RocketMQ 链路，需要先启动 Redis 和 RocketMQ 4.9.6，然后执行：

```powershell
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=mq
```

MQ profile 会开启延迟消息，并关闭提醒和热门请求超时的扫描 fallback：

```yaml
app:
  reservation:
    async:
      enabled: true
      timeout-scan-fallback-enabled: false
    reminder:
      scan-fallback-enabled: false
  delay-message:
    enabled: true
```

### 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://127.0.0.1:5175
```

## 演示账号

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 普通用户 | `tester` | `123456` |
| 锁定用户 | `locked_user` | `123456` |

初始化脚本中的旧密码会在登录成功后自动升级为 BCrypt 密文。

## 核心数据表

| 表名 | 作用 |
| --- | --- |
| `sys_user` | 用户、角色和账号状态 |
| `resource` | 实验室资源 |
| `resource_slot` | 资源可预约时段和剩余名额 |
| `reservation` | 预约记录、签到时间、自动取消截止时间 |
| `reservation_request` | 热门预约异步请求单 |
| `reservation_reminder_task` | 预约前提醒任务账本 |
| `delay_message_outbox` | 延迟消息可靠投递 outbox |
| `user_notification` | 用户通知 |
| `admin_audit_log` | 管理端审计日志 |

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/auth/login` | 登录 |
| `GET` | `/resource/page` | 资源分页 |
| `GET` | `/resource-slot/list` | 查询资源可预约时段 |
| `POST` | `/reservation` | 提交预约 |
| `GET` | `/reservation/request/{requestNo}` | 查询热门预约请求状态 |
| `PUT` | `/reservation/{id}/check-in` | 本人签到 |
| `PUT` | `/reservation/{id}/cancel` | 取消预约 |
| `GET` | `/notification/page` | 我的通知 |
| `GET` | `/admin/dashboard/overview` | 管理端概览 |

## 测试

后端单测覆盖 RocketMQ 延迟等级解析、outbox relay、延迟消息消费、提醒投递、自动取消、热门预约异步处理和预约签到等核心逻辑。

```powershell
cd backend
mvn test
```

当前回归结果：

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
```

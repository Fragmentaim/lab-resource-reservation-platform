# 实验室资源预约系统

这是给数据库课程小组协作使用的干净交付版。仓库只保留后端、完整前端、数据库脚本和启动脚本；日志、构建产物、临时前端、展示图片、压测临时文件都不放进仓库。

## 1. 项目是什么

本项目是一个实验室资源预约管理系统。

普通用户可以登录系统、浏览实验室资源、查看资源可预约时段、提交预约、取消预约、查看自己的预约和通知。

管理员可以维护用户、实验室资源、资源预约时段、预约记录、字典数据，并查看系统总览统计。

课堂展示建议围绕数据库业务讲：用户、资源、时段、预约、通知、字典这些数据如何设计，预约时如何扣减名额，取消预约时如何释放名额，后台如何分页筛选和统计。

## 2. 技术栈

- 后端：Java 17、Spring Boot 4、MyBatis-Plus、MySQL
- 前端：Vue 3、Vite、Naive UI、Pinia、Vue Router、Axios
- 项目里保留但默认关闭的扩展能力：Redis、RocketMQ、异步预约、MQ4 延迟消息、审计日志、缓存、限流

为了方便课程演示，当前默认配置只要求 MySQL。Redis / RocketMQ 相关开关已经在 `backend/src/main/resources/application.yml` 里关闭。

如果要展示后端工程能力，可以启用 `mq` profile。该模式使用 RocketMQ 4.9.6 延迟等级、自推进重投和 outbox，把预约前提醒、未签到超时自动取消、热门预约请求超时失败从“定时扫表”改为“延迟消息触发”。

## 3. 目录结构

```text
lab-booking-course/
  backend/              Spring Boot 后端
  frontend/             Vue 3 + Vite 完整前端
  sql/                  数据库初始化和升级脚本
  scripts/              本地启动脚本
  README.md             项目交接说明
```

不要提交这些目录或文件：

```text
backend/target/
frontend/node_modules/
frontend/dist/
*.log
.idea/
.vscode/
```

这些已经写进 `.gitignore`。

## 4. 本地启动

### 4.1 初始化数据库

先启动 MySQL，然后执行：

```sql
source D:/lab-booking-course/sql/lab-booking-rebuild-init.sql;
```

也可以在 Navicat、DataGrip、MySQL Workbench 等数据库工具里打开并执行：

```text
D:\lab-booking-course\sql\lab-booking-rebuild-init.sql
```

注意：`lab-booking-rebuild-init.sql` 会重建 `lab_booking` 数据库相关表，并插入演示数据。

默认数据库配置在：

```text
backend/src/main/resources/application.yml
```

默认连接信息：

```yaml
url: jdbc:mysql://localhost:3306/lab_booking
username: root
password: ""
```

如果本机 MySQL 密码不是空，改这里的 `spring.datasource.password`。

### 4.2 启动后端

```powershell
cd D:\lab-booking-course\backend
mvn spring-boot:run
```

后端默认地址：

```text
http://127.0.0.1:8081
```

也可以运行：

```powershell
D:\lab-booking-course\scripts\start-backend.ps1
```

如果提示 `mvn` 找不到，说明本机没有配置 Maven，可以用 IDEA 打开 `backend` 目录运行 `LabResourceBookingApplication`，或者安装 Maven 后再用命令行启动。

### 4.3 启动 MQ 增强模式

普通课程演示不需要启动 RocketMQ。如果要验证预约前提醒、未签到自动取消和热门预约请求超时的 MQ4 延迟消息链路，先保证 RocketMQ 4.9.6 broker 和 NameServer 可用，然后执行：

```powershell
cd D:\_Projects\01_Java\lab-booking-course\backend
D:\maven\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run -Dspring-boot.run.profiles=mq
```

MQ 模式配置在：

```text
backend/src/main/resources/application-mq.yml
```

该 profile 会开启 `app.delay-message.enabled`，并关闭提醒到点扫描和热门预约超时扫描 fallback：

```yaml
app.reservation.reminder.scan-fallback-enabled: false
app.reservation.async.timeout-scan-fallback-enabled: false
```

如果是在旧数据库上升级，需要先执行：

```text
sql/delay-message-outbox-upgrade.sql
sql/reservation-auto-cancel-upgrade.sql
```

### 4.4 启动前端

```powershell
cd D:\lab-booking-course\frontend
npm install
npm run dev
```

前端默认地址：

```text
http://127.0.0.1:5175
```

也可以运行：

```powershell
D:\lab-booking-course\scripts\start-frontend.ps1
```

前端接口代理配置在 `frontend/vite.config.js`：

```text
/api -> http://localhost:8081
```

也就是说前端代码里请求 `/api/auth/login`，实际会转发到后端 `/auth/login`。

## 5. 演示账号

| 角色 | 用户名 | 密码 | 说明 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `123456` | 可进入管理端 |
| 普通用户 | `tester` | `123456` | 可预约资源 |
| 锁定用户 | `locked_user` | `123456` | 用于测试禁用状态 |

初始化脚本里的密码是明文 `123456`。后端登录成功后会自动把旧密码升级为 BCrypt 加密格式。

## 6. 前端页面

| 路由 | 页面 | 权限 | 说明 |
| --- | --- | --- | --- |
| `#/login` | 登录 / 注册 | 公开 | 用户登录、注册 |
| `#/resources` | 资源列表 | 登录 | 查看和筛选实验室资源 |
| `#/resources/:id` | 资源详情 | 登录 | 查看资源详情和可预约时段 |
| `#/my-reservations` | 我的预约 | 登录 | 查看、取消自己的预约 |
| `#/notifications` | 我的通知 | 登录 | 查看通知、标记已读 |
| `#/profile` | 个人主页 | 登录 | 查看个人信息和预约概览 |
| `#/admin/overview` | 系统概览 | 管理员 | 统计看板 |
| `#/admin/users` | 用户管理 | 管理员 | 用户新增、编辑、禁用、重置密码 |
| `#/admin/resources` | 资源与时段管理 | 管理员 | 资源和预约时段维护 |
| `#/admin/reservations` | 预约管理 | 管理员 | 查看所有预约 |
| `#/admin/dict` | 字典管理 | 管理员 | 维护资源类型、资源状态等字典 |

## 7. 数据库设计

核心表：

| 表名 | 作用 | 课堂讲解重点 |
| --- | --- | --- |
| `sys_user` | 用户表 | 普通用户、管理员、账号状态 |
| `resource` | 实验室资源表 | 资源编号、名称、类型、状态、位置 |
| `resource_slot` | 预约时段表 | 一个资源有多个时段，每个时段有总名额和剩余名额 |
| `reservation` | 预约记录表 | 连接用户、资源、时段，记录预约状态、签到时间和自动取消截止时间 |
| `user_notification` | 用户通知表 | 保存预约成功、取消、提醒等消息 |
| `reservation_reminder_task` | 提醒任务表 | 保存预约开始前的提醒任务 |
| `sys_dict_type` | 字典类型表 | 资源类型、资源状态等字典分类 |
| `sys_dict_data` | 字典数据表 | 具体字典值，减少硬编码 |

扩展表：

| 表名 | 作用 | 是否建议课堂重点讲 |
| --- | --- | --- |
| `reservation_request` | 热门预约异步请求单 | 不建议重点讲 |
| `delay_message_outbox` | 延迟消息 outbox | 面试时可作为可靠投递重点 |
| `admin_audit_log` | 管理端审计日志 | 不建议重点讲 |
| `admin_audit_outbox` | 审计消息 outbox | 不建议重点讲 |

主要关系：

- 一个用户可以有多条预约记录：`sys_user 1 : N reservation`
- 一个资源可以有多个预约时段：`resource 1 : N resource_slot`
- 一个资源可以被预约多次：`resource 1 : N reservation`
- 一个预约时段可以对应多条预约记录：`resource_slot 1 : N reservation`
- 一个用户可以收到多条通知：`sys_user 1 : N user_notification`
- 一条预约可以生成提醒任务：`reservation 1 : N reservation_reminder_task`
- 一个字典类型包含多个字典数据：`sys_dict_type 1 : N sys_dict_data`

重要约束：

- `sys_user.username` 唯一，避免用户名重复。
- `resource.resource_code` 唯一，避免资源编号重复。
- `reservation.reservation_no` 唯一，保证预约单号唯一。
- `reservation` 通过 `user_id`、`resource_id`、`slot_id` 关联用户、资源和时段。
- `reservation.checked_in_at` 记录用户签到时间，`auto_cancel_deadline` 记录预约开始后允许签到的最后时间。
- `resource_slot.remain_quota` 表示剩余名额，预约成功扣减，取消预约恢复。
- `reservation_request.active_key` 对未完成热门预约请求做唯一约束，保证同一用户同一时段同一时间只有一个待处理请求；请求成功、失败或超时后清空该字段，允许后续重新提交。

常用状态值：

| 类型 | 取值 |
| --- | --- |
| 用户角色 | `ADMIN`、`USER` |
| 用户状态 | `ACTIVE`、`LOCKED` |
| 资源类型 | `TARGET_CAR`、`TEST_FIELD`、`WORKBENCH`、`DEVICE` |
| 资源状态 | `AVAILABLE`、`MAINTAINING`、`DISABLED` |
| 时段类型 | `NORMAL`、`HOT` |
| 时段状态 | `OPEN`、`CLOSED` |
| 预约状态 | `BOOKED`、`CANCELLED`、`FINISHED` |

## 8. 后端接口约定

后端真实接口地址以 `http://127.0.0.1:8081` 为基础。前端开发时统一通过 `/api` 代理访问。

响应格式统一为：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

登录成功后返回 token，除 `/auth/login` 和 `/auth/register` 外，其余接口都需要请求头：

```text
Authorization: Bearer <token>
```

管理员接口需要当前登录用户角色为 `ADMIN`。

## 9. API 清单

### 9.1 认证接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `POST` | `/auth/login` | `/api/auth/login` | 公开 | body: `username`, `password` | 登录，返回 token 和用户信息 |
| `POST` | `/auth/register` | `/api/auth/register` | 公开 | body: `username`, `password`, `confirmPassword`, `nickname`, `phone` | 注册普通用户 |
| `GET` | `/auth/me` | `/api/auth/me` | 登录 | 无 | 获取当前登录用户 |
| `POST` | `/auth/logout` | `/api/auth/logout` | 登录 | 无 | 退出登录 |
| `PUT` | `/auth/password` | `/api/auth/password` | 登录 | body: `oldPassword`, `newPassword`, `confirmPassword` | 修改密码 |

登录示例：

```json
{
  "username": "admin",
  "password": "123456"
}
```

### 9.2 资源接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/resource/list` | `/api/resource/list` | 登录 | query: `name`, `type`, `status` | 资源列表，不分页 |
| `GET` | `/resource/page` | `/api/resource/page` | 登录 | query: `keyword`, `resourceType`, `status`, `pageNum`, `pageSize` | 资源分页 |
| `GET` | `/resource/{id}` | `/api/resource/{id}` | 登录 | path: `id` | 资源详情 |
| `POST` | `/resource` | `/api/resource` | 管理员 | body: `resourceCode`, `resourceName`, `resourceType`, `status`, `location`, `description` | 新增资源 |
| `PUT` | `/resource` | `/api/resource` | 管理员 | body: `id`, `resourceCode`, `resourceName`, `resourceType`, `status`, `location`, `description` | 修改资源 |
| `DELETE` | `/resource/{id}` | `/api/resource/{id}` | 管理员 | path: `id` | 删除资源 |

新增资源示例：

```json
{
  "resourceCode": "LAB-01",
  "resourceName": "人工智能实验室",
  "resourceType": "TEST_FIELD",
  "status": "AVAILABLE",
  "location": "实验楼 301",
  "description": "课程演示资源"
}
```

### 9.3 预约时段接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/resource-slot/list` | `/api/resource-slot/list` | 登录 | query: `resourceId` | 查询某资源下的时段 |
| `GET` | `/resource-slot/page` | `/api/resource-slot/page` | 登录 | query: `resourceId`, `status`, `slotType`, `pageNum`, `pageSize` | 时段分页 |
| `GET` | `/resource-slot/{id}` | `/api/resource-slot/{id}` | 登录 | path: `id` | 时段详情 |
| `POST` | `/resource-slot` | `/api/resource-slot` | 管理员 | body: `resourceId`, `startDatetime`, `endDatetime`, `slotType`, `openTime`, `totalQuota`, `status` | 新增时段 |
| `PUT` | `/resource-slot` | `/api/resource-slot` | 管理员 | body: `id`, `startDatetime`, `endDatetime`, `slotType`, `openTime`, `totalQuota`, `status` | 修改时段 |
| `DELETE` | `/resource-slot/{id}` | `/api/resource-slot/{id}` | 管理员 | path: `id` | 删除时段 |

新增时段示例：

```json
{
  "resourceId": 1,
  "startDatetime": "2026-06-01T09:00:00",
  "endDatetime": "2026-06-01T11:00:00",
  "slotType": "NORMAL",
  "openTime": null,
  "totalQuota": 4,
  "status": "OPEN"
}
```

### 9.4 预约接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/reservation` | `/api/reservation` | 登录 | 无 | 查询我的预约 |
| `GET` | `/reservation/page` | `/api/reservation/page` | 管理员 | query: `userId`, `resourceId`, `status`, `createdFrom`, `createdTo`, `pageNum`, `pageSize` | 查询所有预约分页 |
| `GET` | `/reservation/{id}` | `/api/reservation/{id}` | 登录 | path: `id` | 预约详情，普通用户只能看自己的 |
| `GET` | `/reservation/request/{requestNo}` | `/api/reservation/request/{requestNo}` | 登录 | path: `requestNo` | 查询热门预约异步请求状态 |
| `POST` | `/reservation` | `/api/reservation` | 登录 | body: `resourceId`, `slotId` | 提交预约 |
| `PUT` | `/reservation/{id}/check-in` | `/api/reservation/{id}/check-in` | 登录 | path: `id` | 本人签到，允许预约开始前 30 分钟到开始后 15 分钟 |
| `PUT` | `/reservation/{id}/cancel` | `/api/reservation/{id}/cancel` | 登录 | body: `cancelReason` | 取消预约 |

提交预约示例：

```json
{
  "resourceId": 1,
  "slotId": 1
}
```

取消预约示例：

```json
{
  "cancelReason": "时间冲突，取消预约"
}
```

### 9.5 通知接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/notification/page` | `/api/notification/page` | 登录 | query: `pageNum`, `pageSize`, `read` | 我的通知分页 |
| `GET` | `/notification/unread-count` | `/api/notification/unread-count` | 登录 | 无 | 未读通知数 |
| `PUT` | `/notification/{id}/read` | `/api/notification/{id}/read` | 登录 | path: `id` | 标记单条通知已读 |
| `PUT` | `/notification/read-all` | `/api/notification/read-all` | 登录 | 无 | 全部标记已读 |

### 9.6 用户管理接口

这些接口全部需要管理员权限。

| 方法 | 后端路径 | 前端代理路径 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- |
| `GET` | `/user/page` | `/api/user/page` | query: `keyword`, `role`, `status`, `pageNum`, `pageSize` | 用户分页 |
| `GET` | `/user/{id}` | `/api/user/{id}` | path: `id` | 用户详情 |
| `GET` | `/user/{id}/overview` | `/api/user/{id}/overview` | path: `id` | 用户预约概览 |
| `GET` | `/user/{id}/reservations` | `/api/user/{id}/reservations` | path: `id`; query 同预约分页 | 用户预约记录 |
| `POST` | `/user` | `/api/user` | body: `username`, `password`, `nickname`, `role`, `phone`, `status` | 新增用户 |
| `PUT` | `/user` | `/api/user` | body: `id`, `username`, `nickname`, `role`, `phone`, `status` | 修改用户 |
| `PUT` | `/user/{id}/status` | `/api/user/{id}/status` | body: `status` | 启用 / 禁用用户 |
| `PUT` | `/user/{id}/reset-password` | `/api/user/{id}/reset-password` | path: `id` | 重置密码为 `123456` |

新增用户示例：

```json
{
  "username": "student01",
  "password": "123456",
  "nickname": "学生一号",
  "role": "USER",
  "phone": "13800001111",
  "status": "ACTIVE"
}
```

### 9.7 字典接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/dict/type/list` | `/api/dict/type/list` | 登录 | 无 | 字典类型列表 |
| `GET` | `/dict/data/list` | `/api/dict/data/list` | 登录 | query: `type` | 根据类型查询字典项 |
| `GET` | `/dict/data/page` | `/api/dict/data/page` | 管理员 | query: `type`, `keyword`, `pageNum`, `pageSize` | 字典项分页 |
| `GET` | `/dict/data/{id}` | `/api/dict/data/{id}` | 管理员 | path: `id` | 字典项详情 |
| `POST` | `/dict/data` | `/api/dict/data` | 管理员 | body: `dictType`, `dictLabel`, `dictValue`, `isDefault`, `sortOrder` | 新增字典项 |
| `PUT` | `/dict/data` | `/api/dict/data` | 管理员 | body: `id`, `dictLabel`, `dictValue`, `isDefault`, `sortOrder` | 修改字典项 |
| `DELETE` | `/dict/data/{id}` | `/api/dict/data/{id}` | 管理员 | path: `id` | 删除字典项 |

### 9.8 管理看板接口

| 方法 | 后端路径 | 前端代理路径 | 权限 | 参数 / 请求体 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/admin/dashboard/overview` | `/api/admin/dashboard/overview` | 管理员 | 无 | 管理端系统概览 |

## 10. 业务流程说明

### 10.1 普通预约流程

1. 用户登录，前端保存 token。
2. 用户进入资源列表，查询 `resource`。
3. 用户进入资源详情，查询 `resource_slot`。
4. 用户选择开放时段提交预约。
5. 后端校验资源存在、时段存在、时段属于该资源、时段开放、剩余名额大于 0、用户没有重复预约。
6. 后端扣减 `resource_slot.remain_quota`。
7. 后端插入 `reservation` 记录，状态为 `BOOKED`。
8. 后端写入预约前提醒和未签到自动取消的延迟事件。
9. 用户在“我的预约”里看到新预约。

### 10.2 签到和未签到自动取消流程

1. 用户只能给自己的 `BOOKED` 预约签到。
2. 签到窗口默认为预约开始前 30 分钟到开始后 15 分钟。
3. 用户调用 `PUT /reservation/{id}/check-in` 后，后端写入 `reservation.checked_in_at`。
4. 自动取消延迟消息到期后，如果预约仍是 `BOOKED` 且未签到，后端改为 `CANCELLED`，写入取消原因“超时未签到自动取消”，恢复时段名额并通知用户。
5. 如果预约已签到、已取消或已完成，消费者直接跳过，不重复释放名额。

### 10.3 取消预约流程

1. 用户在“我的预约”里点击取消。
2. 后端校验该预约属于当前用户，并且当前状态允许取消。
3. 后端把 `reservation.status` 改为 `CANCELLED`。
4. 后端释放对应时段名额，增加 `resource_slot.remain_quota`。

### 10.4 管理员维护流程

1. 管理员登录。
2. 进入资源与时段管理。
3. 新增或编辑资源。
4. 给资源配置可预约时段。
5. 在预约管理里查看所有用户预约。
6. 在字典管理里维护资源类型、资源状态等基础数据。

### 10.5 MQ4 延迟消息流程

这部分主要用于后端面试或工程能力展示。前端页面暂不需要修改，后端新增了本人签到接口用于演示未签到自动取消闭环。

统一消息结构：

```json
{
  "eventId": "RESERVATION_REMINDER:10",
  "eventType": "RESERVATION_REMINDER",
  "businessKey": "10",
  "deliverAt": "2026-06-07T10:20:00",
  "payload": "{\"reminderTaskId\":10}"
}
```

消息 topic 为 `reservation-delay`，tag 分为 `reservation-reminder`、`reservation-auto-cancel` 和 `reservation-timeout`。

预约提醒链路：

1. 创建预约成功后，后端仍写入 `reservation_reminder_task`，作为状态账本和幂等依据。
2. 同一个事务里写入 `delay_message_outbox`，记录提醒延迟事件。
3. `DelayMessageRelay` 只负责把 outbox 里的 `PENDING` 事件可靠投递到 RocketMQ，投递成功后标记为 `SENT`。
4. RocketMQ 消费到期后，`DelayMessageMqConsumer` 调用 `ReservationReminderDeliveryService` 创建用户通知，并把提醒任务改为 `SENT`。
5. 如果任务已经 `CANCELLED` 或 `SENT`，消费者直接幂等跳过。

未签到自动取消链路：

1. 创建普通预约成功，或热门异步预约最终成功后，后端设置 `reservation.auto_cancel_deadline = slot_start_datetime + 15 分钟`。
2. 同一个事务里写入 `RESERVATION_AUTO_CANCEL` 延迟事件，payload 包含 `reservationId`。
3. 到期消费时只允许 `BOOKED` 且 `checked_in_at IS NULL` 的预约被自动取消。
4. 取消成功后恢复时段名额，释放 HOT Redis 预占语义，并写入一条用户通知。
5. 如果用户已经签到、预约已取消或已完成，消费者不产生副作用。

热门预约超时链路：

1. 创建热门预约请求时写入 `reservation_request`，同时写入超时延迟事件。
2. 到期消费时按 `requestNo` 查询请求。
3. 只有请求仍是 `PENDING` 才标记为 `FAILED`，并释放 Redis 预占。
4. 如果请求已经 `SUCCESS` 或 `FAILED`，消费者不产生副作用。

热门预约请求使用 `active_key = userId:slotId` 做请求级幂等。未完成请求占用唯一键，重复提交会复用已有请求；请求进入 `SUCCESS/FAILED` 终态或超时失败后清空 `active_key`，避免历史记录影响用户重新提交。

MQ4 只有固定延迟等级，默认是 `1s,5s,10s,30s,1m,2m,3m,4m,5m,6m,7m,8m,9m,10m,20m,30m,1h,2h`。本项目采用“向下取可用等级 + 自推进重投”：超过 2 小时先投 2 小时，消费时如果 `deliverAt` 还没到，就按剩余时间再投一次，直到真正到期再执行业务。

面试时可以这样讲：

- 业务表不再承担到点调度，只保留状态、查询、幂等和审计职责。
- outbox 解决“数据库事务成功但 MQ 投递失败”的可靠性问题。
- MQ4 延迟等级解决大部分短延迟，长延迟通过消费者自推进补齐。
- 消费端按业务状态做幂等，坏 JSON 或业务异常交给 RocketMQ 重试。
- scheduler 只保留历史清理；提醒扫描和超时扫描作为 fallback 开关，MQ 模式默认关闭。

## 11. 交接注意事项

1. 组员接手时先看这个 README，不需要再找其他文档。
2. 如果前端请求失败，先确认后端是否在 `8081`，前端是否在 `5175`。
3. 如果登录失败，先确认数据库脚本是否执行成功，`sys_user` 是否有 `admin` 和 `tester`。
4. 如果后端启动失败，优先检查 MySQL 用户名和密码。
5. 如果端口冲突，后端改 `backend/src/main/resources/application.yml` 的 `server.port`，前端改 `frontend/vite.config.js` 的 `server.port` 或代理地址。
6. 如果新增接口，需要同步更新本 README 的 API 清单。
7. 数据库课程汇报时重点讲核心表和预约流程；面试或后端工程展示时，可以展开 Redis、RocketMQ、Outbox、异步消费和延迟消息。

## 12. Git 协作

当前目录已经是一个干净 Git 仓库。

常用命令：

```powershell
cd D:\lab-booking-course
git status
git add .
git commit -m "说明本次改了什么"
```

后续创建 GitHub 仓库后，添加远程并推送：

```powershell
git remote add origin <你的 GitHub 仓库地址>
git branch -M main
git push -u origin main
```

推荐小组以后只维护这个仓库，不要再从原来的 `D:\lab-resource-booking` 里混复制日志、构建产物和临时目录。

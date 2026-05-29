# 后端说明

后端是实验室资源预约系统的 Spring Boot 服务，主要提供用户、资源、时段、预约、通知、字典和管理端看板相关接口。

## 启动

```powershell
cd D:\lab-booking-course\backend
mvn spring-boot:run
```

默认端口：

```text
http://127.0.0.1:8081
```

## 数据库

默认数据库名为 `lab_booking`，初始化脚本在：

```text
D:\lab-booking-course\sql\lab-booking-rebuild-init.sql
```

数据库连接配置在：

```text
src/main/resources/application.yml
```

## 默认演示账号

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | admin | 123456 |
| 普通用户 | tester | 123456 |

## 主要接口模块

- `AuthController`：登录、注册、退出、当前用户、修改密码
- `ResourceController`：实验室资源查询与维护
- `ResourceSlotController`：资源预约时段查询与维护
- `ReservationController`：提交预约、取消预约、预约查询
- `NotificationController`：用户通知查询和已读处理
- `UserController`：用户管理
- `DictTypeController` / `DictDataController`：字典管理
- `AdminDashboardController`：管理端总览数据

## 课程展示取舍

代码里保留了 Redis、RocketMQ、异步预约、审计日志等工程化能力，但数据库课程展示时建议只讲核心业务链路：

- 用户和角色
- 资源和预约时段
- 预约记录
- 取消预约和名额释放
- 通知提醒
- 字典数据


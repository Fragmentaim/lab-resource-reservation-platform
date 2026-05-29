# 实验室资源预约系统

这是整理给小组协作和数据库课程展示使用的干净版本。仓库只保留可运行的后端、完整前端、数据库脚本和展示文档，原项目里的日志、构建产物、临时前端、压测临时文件都没有放进来。

## 目录结构

```text
lab-booking-course/
  backend/     Spring Boot 后端
  frontend/    Vue 3 + Vite 完整前端
  sql/         数据库初始化与升级脚本
  docs/        E-R 图、功能说明、答辩提纲
  scripts/     本地启动脚本
```

## 系统简介

本项目是一个实验室资源预约管理系统，主要解决“用户预约实验室资源、管理员维护资源与时段”的业务场景。

核心功能包括：

- 用户注册、登录、退出、修改密码
- 实验室资源查询、资源详情查看
- 资源预约时段查询
- 提交预约、取消预约、查看我的预约
- 用户通知查看、标记已读
- 管理员维护用户、资源、预约时段、预约记录、字典数据
- 管理端总览看板

课程展示建议重点讲数据库相关能力：用户、资源、时段、预约、通知、字典这些实体之间的关系，以及预约时的名额扣减、取消预约时的名额释放、后台分页查询和条件筛选。

## 技术栈

- 后端：Java 17、Spring Boot 4、MyBatis-Plus、MySQL
- 前端：Vue 3、Vite、Naive UI、Pinia、Vue Router、Axios
- 可选工程能力：Redis、RocketMQ、异步预约、审计日志

为了方便课程演示，新仓库里的默认配置关闭了 Redis / RocketMQ 相关增强能力，只依赖 MySQL 就能跑主要业务流程。后续如果要展示高并发或消息队列，可以再到 `backend/src/main/resources/application.yml` 里打开对应开关。

## 本地启动

### 1. 初始化数据库

先启动 MySQL，然后执行：

```sql
source D:/lab-booking-course/sql/lab-booking-rebuild-init.sql;
```

或者在数据库工具里打开并执行：

```text
D:\lab-booking-course\sql\lab-booking-rebuild-init.sql
```

注意：这个脚本会重建 `lab_booking` 数据库里的相关表，并插入演示数据。

默认演示账号：

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | admin | 123456 |
| 普通用户 | tester | 123456 |

### 2. 启动后端

```powershell
cd D:\lab-booking-course\backend
mvn spring-boot:run
```

后端默认地址：

```text
http://127.0.0.1:8081
```

也可以运行脚本：

```powershell
D:\lab-booking-course\scripts\start-backend.ps1
```

### 3. 启动前端

```powershell
cd D:\lab-booking-course\frontend
npm install
npm run dev
```

前端默认地址：

```text
http://127.0.0.1:5175
```

也可以运行脚本：

```powershell
D:\lab-booking-course\scripts\start-frontend.ps1
```

## 课堂展示材料

- E-R 图 PNG：`docs/ER-diagram.png`
- E-R 图 SVG：`docs/ER-diagram.svg`
- 功能说明：`docs/项目功能说明.md`
- 数据库设计说明：`docs/数据库设计说明.md`
- 答辩讲解提纲：`docs/答辩讲解提纲.md`

## Git 协作建议

建议小组以后只维护这个干净仓库，不要把原来的临时目录、日志和构建产物提交上去。

推荐流程：

```powershell
cd D:\lab-booking-course
git init
git add .
git commit -m "init lab booking course project"
```

等小组确认仓库名和 GitHub 地址后，再一起添加远程仓库并推送。


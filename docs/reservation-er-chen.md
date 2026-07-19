# 预约链路 E-R 图

本页面包含预约链路的 E-R 图设计。为了解决陈氏（Chen's Style）E-R 图由于属性节点过多导致图片拥挤、排版混乱且难以看清的问题，我们进行了以下优化：

1. **核心链路独立成图**：排除了提醒任务、可靠消息 Outbox、通知等辅助表，只保留最核心的 5 张预约核心表。
2. **属性分组盒化（Subgraph）**：将实体的属性用虚线框（子图）包裹，让属性保持在实体节点附近，防止属性泡泡满屏乱飞导致画面拥挤。
3. **现代 Crow's Foot 关系图**：提供了现代化的数据库关系图，将属性收纳在实体内部，彻底消除拥挤感，适合在技术设计中使用。
4. **交互式高清查看器**：在 `docs/view-er.html` 中提供了一个交互式网页，支持**无限缩放、鼠标拖拽平移、以及导出高清大图（超清分辨率，无损放大不模糊）**。

> [!TIP]
> **如何查看无挤压的高清大图？**
>
> 1. 打开项目中的 [view-er.html](file:///D:/_Projects/01_Java/lab-booking-course/docs/view-er.html) 文件（可直接拖进任何浏览器，或在 VS Code 里右键 Open In Browser）。
> 2. 在查看器中，你可以通过**鼠标滚轮**无限缩放图表，用**鼠标拖拽**平移画布，非常宽敞、毫不挤压。
> 3. 点击 **“导出超清 PNG 图片”**，可以生成一张分辨率超大的无损 PNG 图片，可以直接插入到你的 PPT 汇报或文档中！

---

## 1. 核心预约链路 E-R 图（陈氏风格 - 分组优化版）

*说明：矩形表示实体，椭圆表示属性，菱形表示关系。*

```mermaid
flowchart TB
    %% 实体与属性的 subgraph 分组，防止属性散乱拥挤
    subgraph user_group["用户实体 sys_user"]
        user[用户 sys_user]
        user_id([id])
        user_name([username])
        user_role([role])
        user --- user_id
        user --- user_name
        user --- user_role
    end

    subgraph resource_group["实验室资源实体 resource"]
        resource[实验室资源 resource]
        resource_id([id])
        resource_code([resource_code])
        resource_name([resource_name])
        resource_status([status])
        resource --- resource_id
        resource --- resource_code
        resource --- resource_name
        resource --- resource_status
    end

    subgraph slot_group["预约时段实体 resource_slot"]
        slot[预约时段 resource_slot]
        slot_id([id])
        slot_time([start_datetime / end_datetime])
        slot_type([slot_type])
        slot_quota([total_quota / remain_quota])
        slot_status([status])
        slot --- slot_id
        slot --- slot_time
        slot --- slot_type
        slot --- slot_quota
        slot --- slot_status
    end

    subgraph request_group["热门预约请求实体 reservation_request"]
        request[热门预约请求 request]
        request_id([id])
        request_no([request_no])
        request_active_key([active_key])
        request_status([status])
        request_dispatch([dispatch_status])
        request_reservation_id([reservation_id])
        request --- request_id
        request --- request_no
        request --- request_active_key
        request --- request_status
        request --- request_dispatch
        request --- request_reservation_id
    end

    subgraph reservation_group["预约记录实体 reservation"]
        reservation[预约记录 reservation]
        reservation_id([id])
        reservation_no([reservation_no])
        reservation_status([status])
        reservation_active([is_active])
        reservation_checkin([checked_in_at])
        reservation_deadline([auto_cancel_deadline])
        reservation_source([source_type])
        reservation --- reservation_id
        reservation --- reservation_no
        reservation --- reservation_status
        reservation --- reservation_active
        reservation --- reservation_checkin
        reservation --- reservation_deadline
        reservation --- reservation_source
    end

    %% 关系菱形定义
    rel_user_request{提交}
    rel_user_reservation{创建}
    rel_resource_slot{包含}
    rel_slot_reservation{被预约}
    rel_request_reservation{成功生成}

    %% 实体与关系的连线 (1:N 关系在连线上标注)
    user -->|1| rel_user_request -->|N| request
    user -->|1| rel_user_reservation -->|N| reservation
    resource -->|1| rel_resource_slot -->|N| slot
    slot -->|N| rel_slot_reservation -->|1| reservation
    request -->|1| rel_request_reservation -->|0..1| reservation

    %% 自定义节点与连接线颜色
    classDef default fill:#1e293b,stroke:#475569,stroke-width:1.5px,color:#f8fafc;
    classDef entity fill:#1e3a8a,stroke:#3b82f6,stroke-width:2px,color:#f8fafc;
    classDef attribute fill:#0f172a,stroke:#475569,stroke-width:1px,color:#cbd5e1;
    classDef relation fill:#312e81,stroke:#6366f1,stroke-width:2px,color:#f8fafc;

    class user,resource,slot,request,reservation entity;
    class user_id,user_name,user_role,resource_id,resource_code,resource_name,resource_status,slot_id,slot_time,slot_type,slot_quota,slot_status,request_id,request_no,request_active_key,request_status,request_dispatch,request_reservation_id,reservation_id,reservation_no,reservation_status,reservation_active,reservation_checkin,reservation_deadline,reservation_source attribute;
    class rel_user_request,rel_user_reservation,rel_resource_slot,rel_slot_reservation,rel_request_reservation relation;
```

---

## 2. 核心预约链路 E-R 图（现代 Crow's Foot 风格）

*说明：现代 ER 图风格，属性直接收纳在实体内部，连线表示外键对应关系。这种风格极其精炼，适合直接映射到物理表结构，而且完全没有拥挤感。*

```mermaid
erDiagram
    sys_user ||--o{ reservation_request : "提交"
    sys_user ||--o{ reservation : "创建"
    resource ||--|{ resource_slot : "包含"
    resource_slot ||--o{ reservation : "被预约"
    reservation_request |o--o| reservation : "成功生成"

    sys_user {
        bigint id PK
        varchar username
        varchar role
    }

    resource {
        bigint id PK
        varchar resource_code
        varchar resource_name
        varchar status
    }

    resource_slot {
        bigint id PK
        datetime start_datetime
        datetime end_datetime
        varchar slot_type
        int total_quota
        int remain_quota
        varchar status
    }

    reservation_request {
        bigint id PK
        varchar request_no
        varchar active_key
        varchar status
        varchar dispatch_status
        bigint reservation_id FK
    }

    reservation {
        bigint id PK
        varchar reservation_no
        varchar status
        boolean is_active
        datetime checked_in_at
        datetime auto_cancel_deadline
        varchar source_type
    }
```

---

## 3. 全量预约系统关系图（包含提醒、Outbox 消息、通知）

*说明：展示系统全量实体关系（含异步通知与可靠性消息机制）。*

```mermaid
erDiagram
    sys_user ||--o{ reservation_request : "提交"
    sys_user ||--o{ reservation : "创建"
    resource ||--|{ resource_slot : "包含"
    resource_slot ||--o{ reservation : "被预约"
    reservation_request |o--o| reservation : "成功生成"
    reservation ||--o{ reservation_reminder_task : "生成提醒任务"
    reservation_request ||--o{ message_outbox : "写超时延迟消息"
    reservation_reminder_task ||--o{ message_outbox : "写提醒延迟消息"
    reservation ||--o{ message_outbox : "写取消延迟消息"
    reservation_reminder_task ||--o{ user_notification : "产生提醒通知"
    reservation ||--o{ user_notification : "产生取消通知"

    sys_user {
        bigint id PK
        varchar username
        varchar role
    }
    resource {
        bigint id PK
        varchar resource_code
        varchar resource_name
        varchar status
    }
    resource_slot {
        bigint id PK
        datetime start_datetime
        datetime end_datetime
        varchar slot_type
        int total_quota
        int remain_quota
        varchar status
    }
    reservation_request {
        bigint id PK
        varchar request_no
        varchar active_key
        varchar status
        varchar dispatch_status
        bigint reservation_id FK
    }
    reservation {
        bigint id PK
        varchar reservation_no
        varchar status
        boolean is_active
        datetime checked_in_at
        datetime auto_cancel_deadline
        varchar source_type
    }
    reservation_reminder_task {
        bigint id PK
        varchar remind_type
        datetime plan_send_time
        varchar status
    }
    message_outbox {
        bigint id PK
        varchar event_id
        varchar aggregate_type
        bigint aggregate_id
        varchar event_type
        datetime available_at
        varchar status
        varchar tag
    }
    user_notification {
        bigint id PK
        varchar type
        boolean is_read
        bigint related_reservation_id FK
        bigint reminder_task_id FK
    }
```

---

## 关系与约束说明

### 核心关系说明

| 关系 | 说明 |
| --- | --- |
| 用户提交热门预约请求 | 热门资源走异步确认，先写 `reservation_request`，状态为 `PENDING` |
| 用户创建预约 | 普通预约同步生成 `reservation`；热门预约消费成功后生成 `reservation` |
| 资源包含时段 | 一个实验室资源可以有多个可预约时段 |
| 时段被预约 | 一个时段可以被多个用户预约，但受 `remain_quota` 控制 |
| 请求成功生成预约 | 热门请求从 `PENDING -> PROCESSING -> SUCCESS` 后关联预约 ID |

### 关键物理/逻辑约束

| 约束名 | 作用表与字段 | 作用与业务逻辑说明 |
| --- | --- | --- |
| `uk_reservation_user_slot_active` | `reservation(user_id, slot_id, is_active)` | 防止同一个用户对同一个时段存在多个有效预约 |
| `uk_reservation_request_active_key` | `reservation_request(active_key)` | 防止同一个用户对同一个时段存在多个未完成的热门预约请求 |
| `uk_reservation_reminder_reservation_type` | `reservation_reminder_task(reservation_id, remind_type)` | 防止同一个预约记录重复生成同类型的提醒任务 |
| `uk_message_outbox_event_id` | `message_outbox(event_id)` | 防止同一业务事件重复写入消息 Outbox 造成重复消费 |
| `uk_user_notification_reminder_task` | `user_notification(reminder_task_id)` | 防止同一个提醒任务重复生成用户通知 |

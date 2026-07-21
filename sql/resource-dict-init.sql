CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dict_name VARCHAR(100) NOT NULL,
    dict_type VARCHAR(100) NOT NULL UNIQUE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dict_type VARCHAR(100) NOT NULL,
    dict_label VARCHAR(100) NOT NULL,
    dict_value VARCHAR(100) NOT NULL,
    sort_order INT DEFAULT 0,
    is_default CHAR(1) DEFAULT 'N'
) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

INSERT INTO sys_dict_type (dict_name, dict_type)
SELECT CONVERT(0xE8B584E6BA90E7B1BBE59E8B USING utf8mb4), 'resource_type'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type WHERE dict_type = 'resource_type'
);

INSERT INTO sys_dict_data (dict_type, dict_label, dict_value, sort_order, is_default)
SELECT 'resource_type', CONVERT(0xE79BAEE6A087E8BDA6 USING utf8mb4), 'TARGET_CAR', 1, 'N'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'resource_type' AND dict_value = 'TARGET_CAR'
);

INSERT INTO sys_dict_data (dict_type, dict_label, dict_value, sort_order, is_default)
SELECT 'resource_type', CONVERT(0xE6B58BE8AF95E59CBA USING utf8mb4), 'TEST_FIELD', 2, 'N'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'resource_type' AND dict_value = 'TEST_FIELD'
);

INSERT INTO sys_dict_data (dict_type, dict_label, dict_value, sort_order, is_default)
SELECT 'resource_type', CONVERT(0xE5B7A5E4BD8D USING utf8mb4), 'WORKBENCH', 3, 'N'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'resource_type' AND dict_value = 'WORKBENCH'
);

INSERT INTO sys_dict_data (dict_type, dict_label, dict_value, sort_order, is_default)
SELECT 'resource_type', CONVERT(0xE8AEBEE5A487 USING utf8mb4), 'DEVICE', 4, 'N'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'resource_type' AND dict_value = 'DEVICE'
);

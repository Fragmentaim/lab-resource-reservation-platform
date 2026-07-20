[CmdletBinding()]
param(
    [ValidateRange(1, 100000)]
    [int]$Quota = 50,
    [ValidatePattern('^lab_booking_loadtest$')]
    [string]$Database = 'lab_booking_loadtest',
    [string]$MySqlExecutable = $env:LOADTEST_MYSQL
)

$ErrorActionPreference = 'Stop'
if (-not $MySqlExecutable) { $MySqlExecutable = 'D:\mysql\bin\mysql.exe' }
if (-not (Test-Path -LiteralPath $MySqlExecutable)) { throw "找不到 MySQL 客户端：$MySqlExecutable" }
if ($Database -ne 'lab_booking_loadtest') { throw '安全限制：只允许向 lab_booking_loadtest 创建压测时段。' }

$sql = @"
INSERT INTO resource (resource_code, resource_name, resource_type, status, location, description, created_at, updated_at)
VALUES ('LOADTEST-HOT-001', '热门预约压测资源', 'TEST_FIELD', 'AVAILABLE', 'loadtest', '仅用于隔离压测，请勿用于日常演示。', NOW(), NOW())
ON DUPLICATE KEY UPDATE status = 'AVAILABLE', updated_at = NOW();
SET @resource_id = (SELECT id FROM resource WHERE resource_code = 'LOADTEST-HOT-001' LIMIT 1);
INSERT INTO resource_slot (resource_id, start_datetime, end_datetime, slot_type, open_time, total_quota, remain_quota, status, created_at, updated_at)
VALUES (@resource_id, DATE_ADD(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 2 HOUR), 'HOT', DATE_SUB(NOW(), INTERVAL 1 MINUTE), $Quota, $Quota, 'OPEN', NOW(), NOW());
SELECT @resource_id AS resource_id, LAST_INSERT_ID() AS slot_id, $Quota AS quota;
"@

$row = $sql | & $MySqlExecutable --user=root --host=127.0.0.1 --protocol=TCP --database=$Database --batch --skip-column-names
if ($LASTEXITCODE -ne 0) { throw '创建热门压测时段失败。' }
$values = (($row | Select-Object -Last 1) -split "`t")
if ($values.Count -ne 3) { throw "无法解析 MySQL 返回值：$row" }

[pscustomobject]@{
    ResourceId = [long]$values[0]
    SlotId = [long]$values[1]
    Quota = [int]$values[2]
}

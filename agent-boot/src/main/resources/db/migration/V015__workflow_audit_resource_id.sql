-- M7: Workflow 资源 id 采用 "wfo_/wfv_ + UUID"（40 字符），超出 agent_control_plane_audit.resource_id
-- 原有 CHAR(36) 列宽；控制面其余模块均使用 36 字符裸 UUID。加宽 resource_id 以承接
-- WORKFLOW_* 审计记录，同时保持审计 id 统一为 36 字符裸 UUID。
ALTER TABLE agent_control_plane_audit MODIFY COLUMN resource_id VARCHAR(64) NULL;

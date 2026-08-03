-- M6: 评估执行器以 "eval_<experimentId>" 作为访客引用（44 字符），超出原有 36 字符列宽。
-- 同时修复 V012 早期版本的 agent_eval_* / agent_runtime_span 主键列宽（生成 ID 为
-- "eds_/edv_/ecs_/eev_/eevv_/eex_/eru_/spn_ + UUID"，最长 41 字符）。
ALTER TABLE agent_conversation MODIFY COLUMN visitor_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_task MODIFY COLUMN visitor_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_audit_event MODIFY COLUMN visitor_id VARCHAR(64) NOT NULL;

ALTER TABLE agent_runtime_span MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_dataset MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_dataset MODIFY COLUMN current_version_id VARCHAR(64) NULL;
ALTER TABLE agent_eval_dataset_version MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_dataset_version MODIFY COLUMN dataset_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_case MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_case MODIFY COLUMN dataset_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_case MODIFY COLUMN dataset_version_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_evaluator MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_evaluator MODIFY COLUMN current_version_id VARCHAR(64) NULL;
ALTER TABLE agent_eval_evaluator_version MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_evaluator_version MODIFY COLUMN evaluator_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment MODIFY COLUMN dataset_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment MODIFY COLUMN dataset_version_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment MODIFY COLUMN agent_application_id VARCHAR(64) NULL;
ALTER TABLE agent_eval_experiment MODIFY COLUMN agent_version_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment_run MODIFY COLUMN id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment_run MODIFY COLUMN experiment_id VARCHAR(64) NOT NULL;
ALTER TABLE agent_eval_experiment_run MODIFY COLUMN case_id VARCHAR(64) NOT NULL;

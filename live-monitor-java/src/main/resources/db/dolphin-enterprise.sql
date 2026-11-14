CREATE TABLE IF NOT EXISTS ds_entity (
 cluster_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL, object_id VARCHAR(160) NOT NULL,
 project_code VARCHAR(64) NOT NULL DEFAULT '', workflow_code VARCHAR(64) NOT NULL DEFAULT '',
 workflow_instance_id VARCHAR(64) NOT NULL DEFAULT '', state VARCHAR(64) NOT NULL DEFAULT '',
 etl_date VARCHAR(10) NOT NULL DEFAULT '', payload TEXT NOT NULL, observed_at VARCHAR(32) NOT NULL,
 PRIMARY KEY(cluster_id,kind,object_id)
);
CREATE TABLE IF NOT EXISTS ds_collection (
 cluster_id BIGINT NOT NULL, lane VARCHAR(160) NOT NULL, last_success VARCHAR(32),
 last_attempt VARCHAR(32), watermark VARCHAR(32), failures INT NOT NULL DEFAULT 0,
 error_message VARCHAR(1000), PRIMARY KEY(cluster_id,lane)
);
CREATE TABLE IF NOT EXISTS ds_project_config (
 cluster_id BIGINT NOT NULL, project_code VARCHAR(64) NOT NULL,
 primary_owner VARCHAR(255), backup_owner VARCHAR(255), group_ids VARCHAR(1000) NOT NULL DEFAULT '[]',
 alert_enabled INT NOT NULL DEFAULT 1, updated_at VARCHAR(32),
 PRIMARY KEY(cluster_id,project_code)
);
CREATE TABLE IF NOT EXISTS ds_alert_rule (
 cluster_id BIGINT NOT NULL, scope_type VARCHAR(16) NOT NULL, scope_id VARCHAR(160) NOT NULL,
 rule_type VARCHAR(64) NOT NULL, enabled INT NOT NULL DEFAULT 1, threshold_value DOUBLE,
 business_date VARCHAR(10), deadline VARCHAR(32), PRIMARY KEY(cluster_id,scope_type,scope_id,rule_type)
);
CREATE TABLE IF NOT EXISTS ds_incident (
 cluster_id BIGINT NOT NULL, incident_key VARCHAR(160) NOT NULL, project_code VARCHAR(64) NOT NULL,
 category VARCHAR(64) NOT NULL, message TEXT NOT NULL, group_ids VARCHAR(1000) NOT NULL,
 active INT NOT NULL, opened_at VARCHAR(32) NOT NULL, resolved_at VARCHAR(32),
 PRIMARY KEY(cluster_id,incident_key)
);
CREATE TABLE IF NOT EXISTS ds_node_sample (
 cluster_id BIGINT NOT NULL, node_key VARCHAR(160) NOT NULL, sampled_at VARCHAR(32) NOT NULL,
 payload TEXT NOT NULL, PRIMARY KEY(cluster_id,node_key,sampled_at)
);
CREATE TABLE IF NOT EXISTS ds_incident_member (
 cluster_id BIGINT NOT NULL, incident_key VARCHAR(160) NOT NULL,
 member_kind VARCHAR(32) NOT NULL, member_id VARCHAR(160) NOT NULL,
 PRIMARY KEY(cluster_id,incident_key,member_kind,member_id)
);
CREATE TABLE IF NOT EXISTS ds_node_hourly (
 cluster_id BIGINT NOT NULL, node_key VARCHAR(160) NOT NULL, sampled_at VARCHAR(32) NOT NULL,
 payload TEXT NOT NULL, PRIMARY KEY(cluster_id,node_key,sampled_at)
);

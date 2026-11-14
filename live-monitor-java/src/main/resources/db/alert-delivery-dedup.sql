CREATE TABLE IF NOT EXISTS alert_delivery_dedup (
 event_id BIGINT NOT NULL,
 channel_id BIGINT NOT NULL,
 alert_key VARCHAR(128) NOT NULL,
 PRIMARY KEY(event_id,channel_id,alert_key)
);

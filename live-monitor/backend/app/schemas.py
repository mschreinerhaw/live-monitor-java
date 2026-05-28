from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class ServiceBase(BaseModel):
    service_name: str = Field(..., min_length=1, max_length=120)
    service_type: str = Field(..., min_length=1, max_length=20)
    cluster_name: Optional[str] = None
    host: Optional[str] = None
    port: Optional[int] = Field(default=None, ge=1, le=65535)
    url: Optional[str] = None
    http_method: str = "GET"
    expected_status_code: Optional[int] = Field(default=None, ge=100, le=599)
    response_keyword: Optional[str] = None
    check_timeout_seconds: Optional[float] = Field(default=None, ge=0.2, le=60)
    redis_username: Optional[str] = None
    redis_password: Optional[str] = None
    redis_cluster_mode: bool = False
    zookeeper_check_mode: str = "ruok"
    zookeeper_check_command: str = "ruok"
    zookeeper_expected_nodes: Optional[int] = Field(default=None, ge=1, le=1000)
    check_interval: int = Field(default=60, ge=5, le=86400)
    alert_config_id: Optional[int] = None
    alert_group_id: Optional[int] = None
    enabled: bool = True


class ServiceCreate(ServiceBase):
    pass


class ServiceUpdate(ServiceBase):
    pass


class AlertConfigBase(BaseModel):
    config_name: str = Field(..., min_length=1, max_length=120)
    alert_channel: str = "email"
    alert_email: Optional[str] = None
    alert_mobile: Optional[str] = None
    smtp_host: Optional[str] = None
    smtp_port: Optional[int] = Field(default=None, ge=1, le=65535)
    smtp_user: Optional[str] = None
    smtp_password: Optional[str] = None
    smtp_from: Optional[str] = None
    smtp_use_tls: bool = False
    sms_api_url: Optional[str] = None
    sms_api_token: Optional[str] = None
    sms_username: Optional[str] = None
    sms_password: Optional[str] = None
    sms_password_is_md5: bool = True
    sms_password_md5: Optional[str] = None
    sms_rstype: str = "text"
    sms_ext_code: Optional[str] = None
    enabled: bool = True


class AlertConfigCreate(AlertConfigBase):
    pass


class AlertConfigUpdate(AlertConfigBase):
    pass


class ServiceAlertConfigUpdate(BaseModel):
    alert_config_id: Optional[int] = None


class AlertPolicyBase(BaseModel):
    policy_name: str = Field(..., min_length=1, max_length=120)
    trigger_type: str = Field(..., min_length=1, max_length=40)
    trigger_value: Optional[str] = None
    enabled: bool = True


class AlertPolicyCreate(AlertPolicyBase):
    pass


class AlertPolicyUpdate(AlertPolicyBase):
    pass


class AlertChannelBase(BaseModel):
    channel_name: str = Field(..., min_length=1, max_length=120)
    channel_type: str = "email"
    alert_email: Optional[str] = None
    alert_mobile: Optional[str] = None
    smtp_host: Optional[str] = None
    smtp_port: Optional[int] = Field(default=None, ge=1, le=65535)
    smtp_user: Optional[str] = None
    smtp_password: Optional[str] = None
    smtp_from: Optional[str] = None
    smtp_use_tls: bool = False
    sms_api_url: Optional[str] = None
    sms_api_token: Optional[str] = None
    sms_username: Optional[str] = None
    sms_password: Optional[str] = None
    sms_password_is_md5: bool = True
    sms_password_md5: Optional[str] = None
    sms_rstype: str = "text"
    sms_ext_code: Optional[str] = None
    webhook_url: Optional[str] = None
    enabled: bool = True


class AlertChannelCreate(AlertChannelBase):
    pass


class AlertChannelUpdate(AlertChannelBase):
    pass


class AlertGroupBase(BaseModel):
    group_name: str = Field(..., min_length=1, max_length=120)
    description: Optional[str] = None
    enabled: bool = True
    policy_ids: list[int] = Field(default_factory=list)
    channel_ids: list[int] = Field(default_factory=list)


class AlertGroupCreate(AlertGroupBase):
    pass


class AlertGroupUpdate(AlertGroupBase):
    pass


class ServiceAlertGroupUpdate(BaseModel):
    alert_group_id: Optional[int] = None


class AlertSettingsUpdate(AlertConfigBase):
    pass


class MonitorResultCreate(BaseModel):
    status: str
    response_time_ms: Optional[int] = None
    message: Optional[str] = None

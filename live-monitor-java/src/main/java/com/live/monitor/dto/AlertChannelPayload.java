package com.live.monitor.dto;

import javax.validation.constraints.NotBlank;

public class AlertChannelPayload {
    @NotBlank
    public String channelName;
    @NotBlank
    public String channelType;
    public String alertEmail;
    public String alertCc;
    public String alertMobile;
    public String smtpHost;
    public Integer smtpPort;
    public String smtpUser;
    public String smtpPassword;
    public String smtpFrom;
    public Boolean smtpAuth = true;
    public Boolean smtpUseTls = false;
    public Boolean smtpUseSsl = false;
    public String smtpSslTrust;
    public String smsApiUrl;
    public String smsApiToken;
    public String smsUsername;
    public String smsPassword;
    public Boolean smsPasswordIsMd5 = true;
    public String smsPasswordMd5;
    public String smsRstype = "text";
    public String smsExtCode;
    public String webhookUrl;
    public String dingtalkSecret;
    public String dingtalkAtMobiles;
    public Boolean dingtalkAtAll = false;
    public String wecomMentionedList;
    public String wecomMentionedMobiles;
    public Boolean wecomAtAll = false;
    public Boolean enabled = true;
}

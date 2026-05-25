package com.attribution.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public class ReportRequest {

    @NotBlank(message = "game_id不能为空")
    @JsonAlias("game_id")
    private String gameId;

    @NotBlank(message = "platform不能为空")
    private String platform;

    @NotBlank(message = "event不能为空")
    private String event;

    @JsonAlias({"device_info", "deviceInfo"})
    private DeviceInfo device;
    @JsonAlias({"event_params", "params"})
    private Map<String, Object> eventParams;
    @JsonAlias({"app_info", "appInfo"})
    private AppInfo app;
    private Map<String, Object> user;
    private Map<String, String> fingerprint;
    @JsonAlias("timestamp")
    private Long ts;

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public DeviceInfo getDevice() { return device; }
    public void setDevice(DeviceInfo device) { this.device = device; }
    public Map<String, Object> getEventParams() { return eventParams; }
    public void setEventParams(Map<String, Object> eventParams) { this.eventParams = eventParams; }
    public AppInfo getApp() { return app; }
    public void setApp(AppInfo app) { this.app = app; }
    public Map<String, Object> getUser() { return user; }
    public void setUser(Map<String, Object> user) { this.user = user; }
    public Map<String, String> getFingerprint() { return fingerprint; }
    public void setFingerprint(Map<String, String> fingerprint) { this.fingerprint = fingerprint; }
    public Long getTs() { return ts; }
    public void setTs(Long ts) { this.ts = ts; }

    public static class DeviceInfo {
        private String oaid;
        private String gaid;
        private String idfa;

        public String getOaid() { return oaid; }
        public void setOaid(String oaid) { this.oaid = oaid; }
        public String getGaid() { return gaid; }
        public void setGaid(String gaid) { this.gaid = gaid; }
        public String getIdfa() { return idfa; }
        public void setIdfa(String idfa) { this.idfa = idfa; }
    }

    public static class AppInfo {
        private String version;
        private String channel;
        private String build;

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getBuild() { return build; }
        public void setBuild(String build) { this.build = build; }
    }
}

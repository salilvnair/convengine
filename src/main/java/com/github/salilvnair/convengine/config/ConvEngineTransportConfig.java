package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "convengine.transport")
@Getter
@Setter
public class ConvEngineTransportConfig {

    private Sse sse = new Sse();
    private Stomp stomp = new Stomp();

    @Getter
    @Setter
    public static class Sse {
        private boolean enabled = true;
        private long emitterTimeoutMs = 30 * 60 * 1000L;
    }

    @Getter
    @Setter
    public static class Stomp {
        private boolean enabled = false;
        private String endpoint = "/ws-convengine";
        private String appDestinationPrefix = "/app";
        private String topicPrefix = "/topic";
        private String auditDestinationBase = "/topic/convengine/audit";
        private String allowedOriginPattern = "*";
        private boolean sockJs = true;
        private Broker broker = new Broker();
    }

    @Getter
    @Setter
    public static class Broker {
        private Mode mode = Mode.SIMPLE;
        private List<String> relayDestinationPrefixes = List.of("/topic", "/queue");
        private String relayHost = "localhost";
        private int relayPort = 61613;
        private String clientLogin = "";
        private String clientPasscode = "";
        private String systemLogin = "";
        private String systemPasscode = "";
        private String virtualHost = "";
        private long systemHeartbeatSendIntervalMs = 10000;
        private long systemHeartbeatReceiveIntervalMs = 10000;
    }

    public enum Mode {
        SIMPLE,
        RELAY
    }
}

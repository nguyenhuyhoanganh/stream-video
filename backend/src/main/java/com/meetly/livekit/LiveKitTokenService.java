package com.meetly.livekit;

import com.meetly.meeting.MeetingRole;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomAdmin;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Service
public class LiveKitTokenService {
    private final LiveKitProperties props;

    public LiveKitTokenService(LiveKitProperties props) {
        this.props = props;
    }

    public String createToken(String roomCode, String identity, String displayName,
                              MeetingRole role, Instant expiresAt) {
        AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
        token.setIdentity(identity);
        token.setName(displayName);
        token.setExpiration(Date.from(expiresAt));
        boolean canPublish = role != MeetingRole.ATTENDEE;
        token.addGrants(new RoomJoin(true), new RoomName(roomCode),
                new CanPublish(canPublish), new CanSubscribe(true),
                new CanPublishData(false));
        if (role == MeetingRole.HOST) token.addGrants(new RoomAdmin(true));
        return token.toJwt();
    }

    public String wsUrl() {
        return props.wsUrl();
    }
}

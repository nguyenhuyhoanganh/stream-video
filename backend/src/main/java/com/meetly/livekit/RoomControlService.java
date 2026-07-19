package com.meetly.livekit;

import com.meetly.meeting.MeetingRole;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
public class RoomControlService {
    private final RoomServiceClient client;

    public RoomControlService(LiveKitProperties props) {
        this.client = RoomServiceClient.createClient(props.httpUrl(), props.apiKey(), props.apiSecret());
    }

    /** Mute mọi audio track đang publish của participant. */
    public void muteAllAudio(String room, String identity) {
        try {
            LivekitModels.ParticipantInfo info =
                    client.getParticipant(room, identity).execute().body();
            if (info == null) return;
            for (LivekitModels.TrackInfo track : info.getTracksList()) {
                if (track.getType() == LivekitModels.TrackType.AUDIO) {
                    client.mutePublishedTrack(room, identity, track.getSid(), true).execute();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit mute failed", e);
        }
    }

    /** Cấp lại grants runtime: SPEAKER → canPublish, ATTENDEE → không. */
    public void setRole(String room, String identity, MeetingRole role) {
        try {
            LivekitModels.ParticipantPermission permission =
                    LivekitModels.ParticipantPermission.newBuilder()
                            .setCanSubscribe(true)
                            .setCanPublish(role != MeetingRole.ATTENDEE)
                            .setCanPublishData(false)
                            .build();
            client.updateParticipant(room, identity, null, null, permission).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit updateParticipant failed", e);
        }
    }

    public void kick(String room, String identity) {
        try {
            client.removeParticipant(room, identity).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit removeParticipant failed", e);
        }
    }

    public void endRoom(String room) {
        try {
            client.deleteRoom(room).execute();
        } catch (IOException e) {
            throw new IllegalStateException("LiveKit deleteRoom failed", e);
        }
    }
}

package com.meetly.recording;

import com.meetly.livekit.LiveKitProperties;
import io.livekit.server.EgressServiceClient;
import livekit.LivekitEgress;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class EgressClient {
    private final EgressServiceClient client;
    private final StorageProperties storage;

    public EgressClient(LiveKitProperties livekit, StorageProperties storage) {
        this.client = EgressServiceClient.createClient(
                livekit.httpUrl(), livekit.apiKey(), livekit.apiSecret());
        this.storage = storage;
    }

    /** Bắt đầu ghi RoomComposite → MP4 → S3. Trả về egressId. */
    public String startRoomComposite(String roomCode, String s3Key) {
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.MP4)
                .setFilepath(s3Key)
                .setS3(LivekitEgress.S3Upload.newBuilder()
                        // endpoint theo GÓC NHÌN CONTAINER EGRESS, không phải BE
                        .setEndpoint(storage.uploadEndpoint())
                        .setAccessKey(storage.accessKey())
                        .setSecret(storage.secretKey())
                        .setRegion(storage.region())
                        .setBucket(storage.bucket())
                        .setForcePathStyle(true)
                        .build())
                .build();
        try {
            LivekitEgress.EgressInfo info = client
                    .startRoomCompositeEgress(roomCode, output, "grid")
                    .execute().body();
            if (info == null) throw new IllegalStateException("Egress returned an empty response");
            return info.getEgressId();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start egress", e);
        }
    }

    public void stop(String egressId) {
        try {
            client.stopEgress(egressId).execute();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stop egress", e);
        }
    }
}

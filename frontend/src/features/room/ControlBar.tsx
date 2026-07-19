import { TrackToggle, DisconnectButton, useLocalParticipant } from '@livekit/components-react';
import { Track } from 'livekit-client';
import type { MeetingRole } from '../../api/types';
import { useRecordings, useStartRecording, useStopRecording } from '../recordings/recordingApi';

type Props = {
  meetingId: string;
  role: MeetingRole;
  onRaiseHand: () => void;
  onEnd?: () => void;
};

export function ControlBar({ meetingId, role, onRaiseHand, onEnd }: Props) {
  const { localParticipant } = useLocalParticipant();
  // quyền thật đến từ server (có thể vừa được promote runtime)
  const canPublish = localParticipant.permissions?.canPublish ?? role !== 'ATTENDEE';

  // chỉ HOST được gọi API recordings — guest/attendee gọi sẽ bị 403
  const { data: recs } = useRecordings(meetingId, {
    poll: role === 'HOST',
    enabled: role === 'HOST',
  });
  const startRec = useStartRecording(meetingId);
  const stopRec = useStopRecording(meetingId);
  const recActive = recs?.some((r) => r.status === 'STARTING' || r.status === 'ACTIVE');

  return (
    <div className="flex items-center justify-center gap-3 bg-gray-800 px-4 py-3">
      {canPublish && (
        <span data-testid="publish-controls" className="flex items-center gap-3">
          <TrackToggle source={Track.Source.Microphone} className="lk-button" />
          <TrackToggle source={Track.Source.Camera} className="lk-button" />
          <TrackToggle source={Track.Source.ScreenShare} className="lk-button" />
        </span>
      )}
      <button onClick={onRaiseHand}
              className="bg-yellow-500 text-black rounded-lg px-3 py-2 text-sm font-medium">
        ✋ Giơ tay
      </button>
      {role === 'HOST' && (
        recActive ? (
          <button onClick={() => stopRec.mutate()}
                  className="bg-red-600 text-white rounded-lg px-3 py-2 text-sm animate-pulse">
            ⏹ Dừng ghi
          </button>
        ) : (
          <button onClick={() => startRec.mutate()}
                  className="bg-gray-700 text-white rounded-lg px-3 py-2 text-sm">
            ⏺ Ghi hình
          </button>
        )
      )}
      {role === 'HOST' && onEnd && (
        <button onClick={onEnd}
                className="bg-red-700 text-white rounded-lg px-3 py-2 text-sm font-medium">
          Kết thúc họp
        </button>
      )}
      <DisconnectButton className="lk-button">Rời phòng</DisconnectButton>
    </div>
  );
}

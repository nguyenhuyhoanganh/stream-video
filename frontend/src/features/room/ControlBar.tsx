import { TrackToggle, DisconnectButton, useLocalParticipant } from '@livekit/components-react';
import { Track } from 'livekit-client';
import type { MeetingRole } from '../../api/types';

type Props = {
  role: MeetingRole;
  onRaiseHand: () => void;
  onEnd?: () => void;
};

export function ControlBar({ role, onRaiseHand, onEnd }: Props) {
  const { localParticipant } = useLocalParticipant();
  // quyền thật đến từ server (có thể vừa được promote runtime)
  const canPublish = localParticipant.permissions?.canPublish ?? role !== 'ATTENDEE';

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

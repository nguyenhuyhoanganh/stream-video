import { useEffect, useRef, useState } from 'react';
import {
  GridLayout, FocusLayout, ParticipantTile, RoomAudioRenderer,
  useTracks, useRoomContext, useConnectionState,
} from '@livekit/components-react';
import { ConnectionState, RoomEvent, Track } from 'livekit-client';
import type { MeetingRole } from '../../api/types';
import { ChatPanel } from './ChatPanel';
import { ControlBar } from './ControlBar';
import { ParticipantList } from './ParticipantList';
import { useControlActions } from './controlApi';

type Props = { meetingId: string; role: MeetingRole };

export function RoomLayout({ meetingId, role }: Props) {
  const room = useRoomContext();
  const connectionState = useConnectionState();
  const [promotedToast, setPromotedToast] = useState(false);
  const { end } = useControlActions(meetingId);
  const raiseHandRef = useRef<(() => void) | null>(null);

  const tracks = useTracks(
    [
      { source: Track.Source.Camera, withPlaceholder: true },
      { source: Track.Source.ScreenShare, withPlaceholder: false },
    ],
    { onlySubscribed: false },
  );
  const screenShare = tracks.find((t) => t.source === Track.Source.ScreenShare);

  useEffect(() => {
    const onPermChanged = () => {
      if (room.localParticipant.permissions?.canPublish) {
        setPromotedToast(true);
        setTimeout(() => setPromotedToast(false), 5000);
      }
    };
    room.on(RoomEvent.ParticipantPermissionsChanged, onPermChanged);
    return () => { room.off(RoomEvent.ParticipantPermissionsChanged, onPermChanged); };
  }, [room]);

  return (
    <div className="h-screen flex flex-col bg-gray-900">
      {connectionState === ConnectionState.Reconnecting && (
        <div className="bg-yellow-600 text-black text-center text-sm py-1">
          Reconnecting...
        </div>
      )}
      {promotedToast && (
        <div className="bg-green-600 text-white text-center text-sm py-1">
          You can now speak 🎤
        </div>
      )}
      <div className="flex-1 flex min-h-0">
        <div className="flex-1 min-w-0">
          {screenShare ? (
            <FocusLayout trackRef={screenShare} className="h-full" />
          ) : (
            <GridLayout tracks={tracks} className="h-full">
              <ParticipantTile />
            </GridLayout>
          )}
        </div>
        <aside className="w-72 bg-gray-800 border-l border-gray-700 flex flex-col">
          <div className="flex-1 min-h-0 border-b border-gray-700">
            <ParticipantList meetingId={meetingId} role={role} />
          </div>
          <div className="flex-1 min-h-0">
            <ChatPanel meetingId={meetingId} role={role}
                       registerRaiseHand={(fn) => (raiseHandRef.current = fn)} />
          </div>
        </aside>
      </div>
      {/* raise hand do ChatPanel thực thi (gửi message RAISE_HAND) */}
      <ControlBar meetingId={meetingId} role={role} onRaiseHand={() => raiseHandRef.current?.()}
                  onEnd={role === 'HOST' ? () => end.mutate() : undefined} />
      <RoomAudioRenderer />
    </div>
  );
}

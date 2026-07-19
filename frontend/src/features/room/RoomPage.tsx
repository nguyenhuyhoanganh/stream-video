import { useEffect } from 'react';
import { LiveKitRoom } from '@livekit/components-react';
import '@livekit/components-styles';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useJoinMeeting } from './roomApi';
import { RoomLayout } from './RoomLayout';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';

type PreJoinChoices = { videoEnabled?: boolean; audioEnabled?: boolean };

export function RoomPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const choices = (useLocation().state ?? {}) as PreJoinChoices;
  const user = useAuthStore((s) => s.user);
  const { displayName, setJoin, clear } = useRoomStore();
  const join = useJoinMeeting();

  useEffect(() => {
    if (!code) return;
    join.mutate(
      { code, displayName: user ? undefined : (displayName ?? undefined) },
      { onSuccess: (data) => setJoin(data) },
    );
    return () => clear();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  if (join.isError) {
    // map mã lỗi ổn định của BE (spec 4.8) sang thông báo cho người dùng
    const errCode = isAxiosError(join.error) ? join.error.response?.data?.code : null;
    const detail =
      errCode === 'MEETING_NOT_STARTED' ? 'This meeting has not started yet. Please come back later.'
      : errCode === 'MEETING_ENDED' ? 'This meeting has ended or was cancelled.'
      : errCode === 'NOT_A_MEMBER' ? 'You have not been invited to this meeting.'
      : errCode === 'GUEST_MEETING_FORBIDDEN' ? 'This meeting requires you to sign in.'
      : errCode === 'DISPLAY_NAME_REQUIRED' ? 'Please go back and enter a display name.'
      : 'Could not join this meeting.';
    return (
      <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center gap-4 text-white">
        <p>{detail}</p>
        <button onClick={() => navigate(user ? '/' : `/m/${code}`)}
                className="bg-blue-600 rounded-lg px-4 py-2">
          Back
        </button>
      </div>
    );
  }

  if (!join.data) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center text-white">
        Connecting...
      </div>
    );
  }

  return (
    <div className="h-screen" data-lk-theme="default">
      <LiveKitRoom
        serverUrl={join.data.livekitUrl}
        token={join.data.livekitToken}
        connect
        video={join.data.role !== 'ATTENDEE' && (choices.videoEnabled ?? true)}
        audio={join.data.role !== 'ATTENDEE' && (choices.audioEnabled ?? true)}
        onDisconnected={() => navigate(user ? '/' : `/m/${code}`)}
      >
        <RoomLayout meetingId={join.data.meetingId} role={join.data.role} />
      </LiveKitRoom>
    </div>
  );
}

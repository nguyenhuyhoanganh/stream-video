import { useEffect } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import '@livekit/components-styles';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useJoinMeeting } from './roomApi';
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
    const errCode = isAxiosError(join.error) ? join.error.response?.data?.code : null;
    const detail =
      errCode === 'MEETING_NOT_STARTED' ? 'Phòng họp chưa bắt đầu. Quay lại sau nhé.'
      : errCode === 'MEETING_ENDED' ? 'Phòng họp đã kết thúc hoặc bị hủy.'
      : errCode === 'NOT_A_MEMBER' ? 'Bạn không được mời vào phòng họp này.'
      : errCode === 'GUEST_MEETING_FORBIDDEN' ? 'Phòng này yêu cầu đăng nhập.'
      : errCode === 'DISPLAY_NAME_REQUIRED' ? 'Vui lòng quay lại nhập tên hiển thị.'
      : 'Không vào được phòng họp.';
    return (
      <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center gap-4 text-white">
        <p>{detail}</p>
        <button onClick={() => navigate(user ? '/' : `/m/${code}`)}
                className="bg-blue-600 rounded-lg px-4 py-2">
          Quay lại
        </button>
      </div>
    );
  }

  if (!join.data) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center text-white">
        Đang kết nối...
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
        <VideoConference />
      </LiveKitRoom>
    </div>
  );
}

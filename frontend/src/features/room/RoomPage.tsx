import { useEffect } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import '@livekit/components-styles';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useJoinMeeting } from './roomApi';

type PreJoinChoices = {
  videoEnabled?: boolean;
  audioEnabled?: boolean;
  videoDeviceId?: string;
  audioDeviceId?: string;
};

export function RoomPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const choices = (useLocation().state ?? {}) as PreJoinChoices;
  const join = useJoinMeeting();

  useEffect(() => {
    if (code) join.mutate(code);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  if (join.isError) {
    const detail =
      isAxiosError(join.error) && join.error.response?.data?.code === 'MEETING_NOT_STARTED'
        ? 'Phòng họp chưa bắt đầu. Quay lại sau nhé.'
        : isAxiosError(join.error) && join.error.response?.data?.code === 'MEETING_ENDED'
          ? 'Phòng họp đã kết thúc hoặc bị hủy.'
          : 'Không vào được phòng họp.';
    return (
      <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center gap-4 text-white">
        <p>{detail}</p>
        <button onClick={() => navigate('/')} className="bg-blue-600 rounded-lg px-4 py-2">
          Về trang chính
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
        video={choices.videoEnabled ?? true}
        audio={choices.audioEnabled ?? true}
        onDisconnected={() => navigate('/')}
      >
        <VideoConference />
      </LiveKitRoom>
    </div>
  );
}

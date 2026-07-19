import { PreJoin } from '@livekit/components-react';
import '@livekit/components-styles';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';

export function PreJoinPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const ready = useAuthStore((s) => s.ready);
  const setDisplayName = useRoomStore((s) => s.setDisplayName);

  // PreJoin chỉ đọc defaults lúc mount — phải chờ bootstrapAuth xong
  // để user đăng nhập mở link trực tiếp vẫn được prefill tên
  if (!ready) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center text-white">
        Loading...
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center" data-lk-theme="default">
      <div className="w-full max-w-2xl">
        <h1 className="text-white text-center text-xl mb-4">Room {code}</h1>
        {!user && (
          <p className="text-gray-300 text-center text-sm mb-2">
            You are joining as a guest — enter a display name below
          </p>
        )}
        <PreJoin
          defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
          joinLabel="Join"
          micLabel="Microphone"
          camLabel="Camera"
          onSubmit={(choices) => {
            if (!user && !choices.username.trim()) return;
            setDisplayName(choices.username.trim());
            navigate(`/m/${code}/room`, {
              state: {
                videoEnabled: choices.videoEnabled,
                audioEnabled: choices.audioEnabled,
              },
            });
          }}
        />
      </div>
    </div>
  );
}

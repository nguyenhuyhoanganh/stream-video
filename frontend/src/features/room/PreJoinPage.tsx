import { PreJoin } from '@livekit/components-react';
import '@livekit/components-styles';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';

export function PreJoinPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const setDisplayName = useRoomStore((s) => s.setDisplayName);

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center" data-lk-theme="default">
      <div className="w-full max-w-2xl">
        <h1 className="text-white text-center text-xl mb-4">Phòng {code}</h1>
        {!user && (
          <p className="text-gray-300 text-center text-sm mb-2">
            Bạn đang vào với tư cách khách — nhập tên hiển thị bên dưới
          </p>
        )}
        <PreJoin
          defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
          joinLabel="Vào phòng"
          micLabel="Micro"
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

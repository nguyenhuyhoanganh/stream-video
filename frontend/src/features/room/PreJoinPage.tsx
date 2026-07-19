import { PreJoin } from '@livekit/components-react';
import '@livekit/components-styles';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';

export function PreJoinPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center" data-lk-theme="default">
      <div className="w-full max-w-2xl">
        <h1 className="text-white text-center text-xl mb-4">Phòng {code}</h1>
        <PreJoin
          defaults={{ username: user?.fullName ?? '', videoEnabled: true, audioEnabled: true }}
          joinLabel="Vào phòng"
          micLabel="Micro"
          camLabel="Camera"
          onSubmit={(choices) => {
            navigate(`/m/${code}/room`, {
              state: {
                videoEnabled: choices.videoEnabled,
                audioEnabled: choices.audioEnabled,
                videoDeviceId: choices.videoDeviceId,
                audioDeviceId: choices.audioDeviceId,
              },
            });
          }}
        />
      </div>
    </div>
  );
}

import { useParticipants } from '@livekit/components-react';
import type { MeetingRole } from '../../api/types';
import { useControlActions } from './controlApi';

type Props = { meetingId: string; role: MeetingRole };

export function ParticipantList({ meetingId, role }: Props) {
  const participants = useParticipants();
  const { mute, promote, demote, kick } = useControlActions(meetingId);
  const isHost = role === 'HOST';

  return (
    <div className="h-full overflow-y-auto">
      <h3 className="px-3 py-2 text-sm font-semibold text-gray-300">
        Người tham gia ({participants.length})
      </h3>
      {participants.map((p) => (
        <div key={p.identity}
             className="px-3 py-2 flex items-center justify-between text-sm text-gray-100">
          <span>
            {p.name || p.identity}
            {p.isLocal && ' (bạn)'}
            {p.permissions?.canPublish === false && ' 👁'}
          </span>
          {isHost && !p.isLocal && (
            <span className="flex gap-1">
              <button title="Mute" onClick={() => mute.mutate(p.identity)}
                      className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">🔇</button>
              {p.permissions?.canPublish === false ? (
                <button title="Cho phát biểu" onClick={() => promote.mutate(p.identity)}
                        className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">🎤</button>
              ) : (
                <button title="Hạ xuống khán giả" onClick={() => demote.mutate(p.identity)}
                        className="px-1.5 rounded bg-gray-700 hover:bg-gray-600">⬇️</button>
              )}
              <button title="Mời ra" onClick={() => kick.mutate(p.identity)}
                      className="px-1.5 rounded bg-red-900 hover:bg-red-800">✖</button>
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { MeetingRole } from '../../api/types';
import { useAuthStore } from '../../stores/authStore';
import { useRoomStore } from '../../stores/roomStore';
import { useChatSocket } from './useChatSocket';

type Props = {
  meetingId: string;
  role: MeetingRole;
  registerRaiseHand?: (fn: () => void) => void;
};

export function ChatPanel({ meetingId, role, registerRaiseHand }: Props) {
  const accessToken = useAuthStore((s) => s.accessToken);
  const chatToken = useRoomStore((s) => s.join?.chatToken ?? null);
  const token = chatToken ?? accessToken;
  const { messages, connected, send, removeLocal } = useChatSocket(meetingId, token);
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    registerRaiseHand?.(() => send('giơ tay', 'RAISE_HAND'));
  }, [registerRaiseHand, send]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!draft.trim()) return;
    send(draft.trim());
    setDraft('');
  }

  async function onDelete(messageId: string) {
    await api.delete(`/meetings/${meetingId}/messages/${messageId}`);
    removeLocal(messageId);
  }

  return (
    <div className="h-full flex flex-col">
      <h3 className="px-3 py-2 text-sm font-semibold text-gray-300">
        Chat {connected ? '' : '(đang kết nối...)'}
      </h3>
      <div className="flex-1 overflow-y-auto px-3 space-y-1">
        {messages.map((m) =>
          m.type === 'RAISE_HAND' ? (
            <p key={m.id} className="text-yellow-400 text-sm">
              ✋ <b>{m.senderDisplayName}</b> giơ tay
            </p>
          ) : (
            <p key={m.id} className="text-sm text-gray-100 group">
              <b className="text-gray-400">{m.senderDisplayName}:</b> {m.content}
              {role === 'HOST' && (
                <button onClick={() => void onDelete(m.id)}
                        className="ml-2 hidden group-hover:inline text-red-400 text-xs">
                  xóa
                </button>
              )}
            </p>
          ),
        )}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={onSubmit} className="p-2 flex gap-2">
        <input className="flex-1 bg-gray-700 text-gray-100 rounded px-2 py-1 text-sm"
               value={draft} onChange={(e) => setDraft(e.target.value)}
               placeholder="Nhắn tin..." />
        <button className="bg-blue-600 text-white rounded px-3 text-sm" type="submit">
          Gửi
        </button>
      </form>
    </div>
  );
}

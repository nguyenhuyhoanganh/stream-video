import { useCallback, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api } from '../../api/client';
import type { ChatEvent, ChatMessageDto } from '../../api/types';

export function mergeMessages(
  current: ChatMessageDto[], incoming: ChatMessageDto[],
): ChatMessageDto[] {
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
  );
}

export function useChatSocket(meetingId: string, token: string | null) {
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const lastCreatedAtRef = useRef<string | null>(null);

  useEffect(() => {
    lastCreatedAtRef.current = messages.length
      ? messages[messages.length - 1].createdAt : null;
  }, [messages]);

  useEffect(() => {
    if (!token) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/meetings/${meetingId}/chat`, (frame) => {
          const event = JSON.parse(frame.body) as ChatEvent;
          if (event.kind === 'MESSAGE' && event.message) {
            setMessages((cur) => mergeMessages(cur, [event.message!]));
          } else if (event.kind === 'MESSAGE_DELETED' && event.messageId) {
            setMessages((cur) => cur.filter((m) => m.id !== event.messageId));
          }
        });
        // backfill missed messages (first connect: the 50 most recent)
        const params = lastCreatedAtRef.current
          ? { after: lastCreatedAtRef.current } : { limit: 50 };
        void api
          .get<ChatMessageDto[]>(`/meetings/${meetingId}/messages`, {
            params,
            headers: { Authorization: `Bearer ${token}` },
          })
          .then(({ data }) => setMessages((cur) => mergeMessages(cur, data)));
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;
    return () => { void client.deactivate(); clientRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [meetingId, token]);

  const send = useCallback(
    (content: string, type: 'TEXT' | 'RAISE_HAND' = 'TEXT') => {
      clientRef.current?.publish({
        destination: `/app/meetings/${meetingId}/chat`,
        body: JSON.stringify({ content, type }),
      });
    },
    [meetingId],
  );

  const removeLocal = useCallback((messageId: string) => {
    setMessages((cur) => cur.filter((m) => m.id !== messageId));
  }, []);

  return { messages, connected, send, removeLocal };
}

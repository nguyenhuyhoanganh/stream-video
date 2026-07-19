import { describe, expect, it } from 'vitest';
import { mergeMessages } from './useChatSocket';
import type { ChatMessageDto } from '../../api/types';

const msg = (id: string, at: string): ChatMessageDto => ({
  id, meetingId: 'm', senderIdentity: 's', senderDisplayName: 'S',
  content: id, type: 'TEXT', createdAt: at,
});

describe('mergeMessages', () => {
  it('merges without duplicating ids and sorts by createdAt', () => {
    const current = [msg('a', '2026-07-18T10:00:00Z'), msg('b', '2026-07-18T10:01:00Z')];
    const incoming = [msg('b', '2026-07-18T10:01:00Z'), msg('c', '2026-07-18T10:00:30Z')];
    const out = mergeMessages(current, incoming);
    expect(out.map((m) => m.id)).toEqual(['a', 'c', 'b']);
  });
});

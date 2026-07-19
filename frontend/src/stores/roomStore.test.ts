import { beforeEach, describe, expect, it } from 'vitest';
import { useRoomStore } from './roomStore';

describe('roomStore', () => {
  beforeEach(() => useRoomStore.getState().clear());

  it('stores the join info', () => {
    useRoomStore.getState().setJoin({
      meetingId: 'm1', livekitUrl: 'ws://x', livekitToken: 't', role: 'ATTENDEE', chatToken: 'ct',
    });
    expect(useRoomStore.getState().join?.role).toBe('ATTENDEE');
  });

  it('clear reset', () => {
    useRoomStore.getState().setJoin({
      meetingId: 'm1', livekitUrl: 'ws://x', livekitToken: 't', role: 'HOST', chatToken: null,
    });
    useRoomStore.getState().clear();
    expect(useRoomStore.getState().join).toBeNull();
  });
});

import { create } from 'zustand';
import type { JoinResponse } from '../api/types';

type RoomState = {
  join: JoinResponse | null;
  displayName: string | null;
  setJoin: (join: JoinResponse) => void;
  setDisplayName: (name: string) => void;
  clear: () => void;
};

export const useRoomStore = create<RoomState>((set) => ({
  join: null,
  displayName: null,
  setJoin: (join) => set({ join }),
  setDisplayName: (displayName) => set({ displayName }),
  clear: () => set({ join: null, displayName: null }),
}));

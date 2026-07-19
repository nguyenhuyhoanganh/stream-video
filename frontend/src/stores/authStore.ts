import { create } from 'zustand';
import type { User } from '../api/types';

type AuthState = {
  user: User | null;
  accessToken: string | null;
  /** true sau khi bootstrapAuth chạy xong (dù thành công hay không) */
  ready: boolean;
  setAuth: (user: User, accessToken: string) => void;
  setReady: () => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  ready: false,
  setAuth: (user, accessToken) => set({ user, accessToken }),
  setReady: () => set({ ready: true }),
  clear: () => set({ user: null, accessToken: null, ready: get().ready }),
}));

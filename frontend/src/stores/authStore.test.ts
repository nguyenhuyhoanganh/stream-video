import { describe, expect, it, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clear());

  it('starts signed out and not ready', () => {
    const s = useAuthStore.getState();
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
    expect(s.ready).toBe(false);
  });

  it('setAuth stores the user and token', () => {
    useAuthStore.getState().setAuth(
      { id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    const s = useAuthStore.getState();
    expect(s.user?.email).toBe('a@b.c');
    expect(s.accessToken).toBe('tok');
  });

  it('clear drops the user but keeps ready', () => {
    const st = useAuthStore.getState();
    st.setReady();
    st.setAuth({ id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().ready).toBe(true);
  });
});

import { describe, expect, it, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clear());

  it('bắt đầu chưa đăng nhập, chưa ready', () => {
    const s = useAuthStore.getState();
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
    expect(s.ready).toBe(false);
  });

  it('setAuth lưu user + token', () => {
    useAuthStore.getState().setAuth(
      { id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    const s = useAuthStore.getState();
    expect(s.user?.email).toBe('a@b.c');
    expect(s.accessToken).toBe('tok');
  });

  it('clear xóa user nhưng giữ ready', () => {
    const st = useAuthStore.getState();
    st.setReady();
    st.setAuth({ id: '1', email: 'a@b.c', fullName: 'A' }, 'tok');
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().ready).toBe(true);
  });
});

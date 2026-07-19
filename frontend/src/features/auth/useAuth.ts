import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { useAuthStore } from '../../stores/authStore';
import type { AuthResponse } from '../../api/types';

export function useAuth() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const clear = useAuthStore((s) => s.clear);

  return {
    async login(email: string, password: string) {
      const { data } = await api.post<AuthResponse>('/auth/login', { email, password });
      setAuth(data.user, data.accessToken);
      navigate('/');
    },
    async register(email: string, password: string, fullName: string) {
      const { data } = await api.post<AuthResponse>('/auth/register', { email, password, fullName });
      setAuth(data.user, data.accessToken);
      navigate('/');
    },
    async logout() {
      await api.post('/auth/logout');
      clear();
      navigate('/login');
    },
  };
}

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
      // Clear the local session even when the call fails: a network blip must not
      // leave the user staring at a dashboard they believe they signed out of.
      // The refresh token stays valid server-side until it expires, which is why
      // the request is still attempted first.
      try {
        await api.post('/auth/logout');
      } catch {
        // ignored on purpose — see above
      } finally {
        clear();
        navigate('/login');
      }
    },
  };
}

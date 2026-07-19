import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../stores/authStore';
import type { AuthResponse } from './types';

export const api = axios.create({ baseURL: '/api/v1', withCredentials: true });

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let refreshing: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  try {
    const { data } = await axios.post<AuthResponse>('/api/v1/auth/refresh', null, {
      withCredentials: true,
    });
    useAuthStore.getState().setAuth(data.user, data.accessToken);
    return data.accessToken;
  } catch {
    return null;
  }
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined;
    const isAuthRoute = config?.url?.startsWith('/auth');
    if (error.response?.status === 401 && config && !config._retry && !isAuthRoute) {
      config._retry = true;
      refreshing ??= refreshAccessToken().finally(() => (refreshing = null));
      const token = await refreshing;
      if (token) return api(config);
      // Guests have no account to sign back in with, so bouncing them to /login just
      // throws them out of the meeting. Only redirect users who were signed in.
      const wasLoggedIn = useAuthStore.getState().user !== null;
      useAuthStore.getState().clear();
      if (wasLoggedIn) window.location.assign('/login');
    }
    throw error;
  },
);

/** Called once at app load to restore the session from the refresh cookie. */
export async function bootstrapAuth(): Promise<void> {
  await refreshAccessToken();
  useAuthStore.getState().setReady();
}

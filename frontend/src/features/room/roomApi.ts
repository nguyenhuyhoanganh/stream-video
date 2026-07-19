import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { JoinResponse } from '../../api/types';

export function useJoinMeeting() {
  return useMutation({
    mutationFn: async (code: string) =>
      (await api.post<JoinResponse>(`/meetings/${code}/join`)).data,
  });
}

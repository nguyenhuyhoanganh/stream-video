import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { JoinResponse } from '../../api/types';

export function useJoinMeeting() {
  return useMutation({
    mutationFn: async (input: { code: string; displayName?: string }) =>
      (await api.post<JoinResponse>(`/meetings/${input.code}/join`,
        input.displayName ? { displayName: input.displayName } : undefined)).data,
  });
}

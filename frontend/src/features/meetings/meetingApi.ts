import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Meeting } from '../../api/types';

export type CreateMeetingInput = {
  title: string;
  description?: string;
  scheduledStartAt?: string;
  scheduledEndAt?: string;
};

export function useMyMeetings() {
  return useQuery({
    queryKey: ['meetings'],
    queryFn: async () => (await api.get<Meeting[]>('/meetings')).data,
  });
}

export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateMeetingInput) =>
      (await api.post<Meeting>('/meetings', input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['meetings'] }),
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Meeting } from '../../api/types';

export type CreateMeetingInput = {
  title: string;
  description?: string;
  scheduledStartAt?: string;
  scheduledEndAt?: string;
  roomType?: 'MEETING' | 'WEBINAR';
};

export type MemberDto = { id: string; email: string | null; role: 'SPEAKER' | 'ATTENDEE' };
// a null email means the member was promoted in-room and only has a userId

export function useMembers(meetingId: string | null) {
  return useQuery({
    queryKey: ['members', meetingId],
    enabled: !!meetingId,
    queryFn: async () => (await api.get<MemberDto[]>(`/meetings/${meetingId}/members`)).data,
  });
}

export function useAddMember(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { email: string; role: 'SPEAKER' | 'ATTENDEE' }) =>
      (await api.post(`/meetings/${meetingId}/members`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['members', meetingId] }),
  });
}

export function useRemoveMember(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (memberId: string) =>
      api.delete(`/meetings/${meetingId}/members/${memberId}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['members', meetingId] }),
  });
}

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

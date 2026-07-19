import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';

export type RecordingDto = {
  id: string;
  status: 'STARTING' | 'ACTIVE' | 'COMPLETED' | 'FAILED';
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
};

export function useRecordings(meetingId: string, opts?: { poll?: boolean; enabled?: boolean }) {
  return useQuery({
    queryKey: ['recordings', meetingId],
    queryFn: async () =>
      (await api.get<RecordingDto[]>(`/meetings/${meetingId}/recordings`)).data,
    refetchInterval: opts?.poll ? 10_000 : false,
    enabled: opts?.enabled ?? true,
  });
}

export function useStartRecording(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => api.post(`/meetings/${meetingId}/recordings/start`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['recordings', meetingId] }),
  });
}

export function useStopRecording(meetingId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => api.post(`/meetings/${meetingId}/recordings/stop`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['recordings', meetingId] }),
  });
}

export async function fetchPlaybackUrl(recordingId: string): Promise<string> {
  return (await api.get<{ url: string }>(`/recordings/${recordingId}/playback-url`)).data.url;
}

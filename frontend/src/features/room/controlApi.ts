import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';

function participantAction(meetingId: string, action: string) {
  return (identity: string) =>
    api.post(`/meetings/${meetingId}/participants/${identity}/${action}`);
}

export function useControlActions(meetingId: string) {
  const mute = useMutation({ mutationFn: participantAction(meetingId, 'mute') });
  const promote = useMutation({ mutationFn: participantAction(meetingId, 'promote') });
  const demote = useMutation({ mutationFn: participantAction(meetingId, 'demote') });
  const kick = useMutation({ mutationFn: participantAction(meetingId, 'kick') });
  const end = useMutation({ mutationFn: async () => api.post(`/meetings/${meetingId}/end`) });
  return { mute, promote, demote, kick, end };
}

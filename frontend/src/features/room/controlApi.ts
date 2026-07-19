import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/client';

export function useControlActions(meetingId: string) {
  const act = (action: string) =>
    // eslint-disable-next-line react-hooks/rules-of-hooks -- 4 lần gọi cố định mỗi render, thứ tự không đổi
    useMutation({
      mutationFn: async (identity: string) =>
        api.post(`/meetings/${meetingId}/participants/${identity}/${action}`),
    });
  const mute = act('mute');
  const promote = act('promote');
  const demote = act('demote');
  const kick = act('kick');
  const end = useMutation({ mutationFn: async () => api.post(`/meetings/${meetingId}/end`) });
  return { mute, promote, demote, kick, end };
}

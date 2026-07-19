import { useState, type FormEvent } from 'react';
import { useAddMember, useMembers, useRemoveMember } from './meetingApi';

type Props = { meetingId: string; onClose: () => void };

export function MembersDialog({ meetingId, onClose }: Props) {
  const { data: members } = useMembers(meetingId);
  const addMember = useAddMember(meetingId);
  const removeMember = useRemoveMember(meetingId);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<'SPEAKER' | 'ATTENDEE'>('ATTENDEE');

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    await addMember.mutateAsync({ email, role });
    setEmail('');
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center"
         onClick={onClose}>
      <div className="bg-white rounded-xl p-5 w-[28rem]" onClick={(e) => e.stopPropagation()}>
        <h3 className="font-semibold mb-3">Thành viên được mời</h3>
        <form onSubmit={onAdd} className="flex gap-2 mb-3">
          <input className="flex-1 border rounded px-2 py-1" type="email" required
                 placeholder="email@congty.vn" value={email}
                 onChange={(e) => setEmail(e.target.value)} />
          <select className="border rounded px-2 py-1" value={role}
                  onChange={(e) => setRole(e.target.value as 'SPEAKER' | 'ATTENDEE')}>
            <option value="ATTENDEE">Khán giả</option>
            <option value="SPEAKER">Diễn giả</option>
          </select>
          <button className="bg-blue-600 text-white rounded px-3" type="submit">Thêm</button>
        </form>
        <ul className="divide-y max-h-64 overflow-y-auto">
          {members?.map((m) => (
            <li key={m.id} className="py-2 flex justify-between text-sm">
              <span>{m.email ?? '(thành viên trong phòng)'} — {m.role === 'SPEAKER' ? 'Diễn giả' : 'Khán giả'}</span>
              <button onClick={() => removeMember.mutate(m.id)}
                      className="text-red-600">Xóa</button>
            </li>
          ))}
          {members?.length === 0 && <li className="py-2 text-sm text-gray-500">Chưa mời ai</li>}
        </ul>
        <button onClick={onClose} className="mt-3 w-full border rounded py-1.5">Đóng</button>
      </div>
    </div>
  );
}
